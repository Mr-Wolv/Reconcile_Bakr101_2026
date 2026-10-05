package com.reconcile.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.provider.WebhookSignature;
import com.reconcile.service.InboundEventWorker;
import com.reconcile.service.ReconciliationService;
import com.reconcile.service.ReconciliationWorker;
import com.reconcile.support.SharedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Event ordering: the property the whole suite was, until now, quietly assuming.
 *
 * <p>Every other integration test delivers provider events in the order the provider emitted them.
 * That is a courtesy the provider extends in the happy path and withdraws the moment a worker, a
 * network or a batch boundary reorders them. Defect 21 was exactly this: the inbound worker applies
 * a claimed batch in whatever order it holds the rows, a late {@code payment.created} overwrote
 * {@code SETTLED} with {@code AUTHORIZED} on the provider-side row, and the next reconciliation
 * batch reported {@code STATUS_MISMATCH} against a payment that was correctly settled.
 *
 * <p>No ordered test can find that class of bug. A test that delivers {@code created → captured →
 * settled} and asserts {@code SETTLED} is not testing order-independence; it is testing order. This
 * class tests order-independence, in two forms:
 *
 * <ul>
 *   <li><b>Exhaustive permutations</b> ({@code C-ORD-01} … {@code C-ORD-03}). A lifecycle is small
 *       enough to enumerate every ordering of — four events is 24, three is 6 — so the coverage is
 *       complete and deterministic rather than random. A single failure names the exact ordering
 *       that broke, which a random test can only approximate.
 *   <li><b>Randomised concurrency</b> ({@code C-ORD-04}). Sixteen payments, sixty-four events,
 *       delivered from sixteen threads released together, drained by eight workers through
 *       {@code FOR UPDATE SKIP LOCKED}. Nothing coordinates the arrival order here, which is the
 *       point: it is the closest in-process reproduction of a real provider's burst.
 * </ul>
 *
 * <p>The order is forced, not hoped for. After the events are delivered, {@code next_attempt_at} is
 * rewritten to the order under test, because the claim query orders by it — so a permutation is
 * reproduced exactly, every run, with no sleep and no polling. {@code docs/spec/08 §8} rules out
 * {@code Thread.sleep} as a way to manufacture a race; manufacturing the ordering in SQL and then
 * releasing threads on a barrier produces the same interleaving deterministically.
 *
 * <p>What is asserted is <b>convergence</b>, and it is asserted the way the engine would judge a
 * real payment: the payment reaches the state the provider's account implies, the provider-side row
 * agrees with it, the money posted exactly once per business fact, and reconciliation agrees on both
 * sides. A run that ends {@code SETTLED} on our side and {@code AUTHORIZED} on the provider's has
 * not passed, however reasonable it looks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EventOrderingIT {

    private static final String SECRET = "webhook-secret-token";
    private static final String OPERATOR = "operator-secret-token";

    /** Workers draining the inbox at once. Several is the point: one would serialise the race out. */
    private static final int WORKERS = 8;

    /** Payments in the randomised concurrency test. */
    private static final int FANOUT = 16;

    /** Drain passes before a still-busy inbox is called a bug rather than a scheduling artefact. */
    private static final int ROUNDS = 25;

    /**
     * Wall-clock budget for one drain, in addition to the round cap.
     *
     * <p>The worker does not retry a failed event immediately: it backs off, base 2s with full
     * jitter. A drain that only counts rounds can therefore give up before the first retry is even
     * due — 25 rounds completed in 1.7s, while an event deferred behind a lock or a deadlock is
     * legitimately not claimable for a couple of seconds. That produced a failure that said "the
     * inbox never went quiet" about an event that had not yet been given a chance to be retried.
     *
     * <p>This does not weaken the assertion: the inbox still has to go quiet, and a genuinely
     * stuck event still fails, just after a span that spans several backoff windows rather than
     * less than one.
     */
    private static final long DRAIN_BUDGET_MILLIS = 30_000L;

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    Sql sql;

    @Autowired
    Clock clock;

    @Autowired
    InboundEventWorker worker;

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    ReconciliationWorker batches;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedPostgres::jdbcUrl);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("reconcile.security.operator-token", () -> OPERATOR);
        registry.add("reconcile.security.admin-token", () -> "admin-secret-token");
        registry.add("reconcile.provider.webhook-secret", () -> SECRET);
        // This class owns the drain. A scheduled poll racing a deliberate ordering would make the
        // permutations unreproducible, which is the one property this file exists to establish.
        registry.add("reconcile.provider.event-poll-interval", () -> "1h");
        // Same reason, for the reconciliation scheduler: a background poll completing a batch this
        // class is mid-way through arranging would make the permutations unreproducible.
        registry.add("reconcile.reconciliation.poll-interval", () -> "1h");
    }

    @BeforeEach
    void reset() {
        clearDatabase();
    }

    /**
     * Clears the schema between scenarios.
     *
     * <p>Called per permutation as well as per test: each ordering is a scenario in its own right,
     * and leaving the previous one's rows behind would make the counts assert the accumulation of
     * every ordering tried so far instead of this one.
     */
    private void clearDatabase() {
        sql.sql("""
                TRUNCATE payment_state_history, payment_ledger_transactions, ledger_entries,
                         ledger_transactions, account_balances, payments, payout_lines,
                         payout_records, settlement_record_lines, settlement_records,
                         provider_transactions, provider_events, provider_event_deliveries,
                         idempotency_records, reconciliation_cases, reconciliation_results,
                         reconciliation_subjects, reconciliation_batches, case_events,
                         audit_events, fee_policies
                RESTART IDENTITY CASCADE
                """).update();
        sql.sql("INSERT INTO fee_policies (id, code, bps, currency, effective_from) "
                + "VALUES ('fp_default','DEFAULT',300,'EGP','2026-01-01T00:00:00Z')").update();
        sql.sql("""
                INSERT INTO account_balances (account_id, currency)
                SELECT a.id, c.code FROM ledger_accounts a CROSS JOIN currency_units c WHERE c.active
                """).update();
    }

    // ------------------------------------------------------------------ C-ORD-01

    /**
     * The defect-21 lifecycle, in every one of its 24 orderings.
     *
     * <p>The payment is captured through our own API and the provider then tells us about it four
     * times. Only the order those four are <i>applied</i> varies. Whatever order the worker happens
     * to hold the rows in, the payment must end SETTLED, the provider row must say SETTLED, and the
     * capture and the settlement must have posted exactly once each.
     */
    @Test
    @DisplayName("C-ORD-01: all 24 orderings of created/authorized/captured/settlement converge on "
            + "SETTLED, post once each, and reconcile without a discrepancy")
    void everyOrderingOfTheSettlementLifecycleConverges() throws Exception {
        for (List<Integer> permutation : permutations(4)) {
            clearDatabase();
            String providerTxn = "psp_" + unique();
            String paymentId = createCapturedPayment(providerTxn);
            String settlementId = "sset_" + unique();

            List<String> events = List.of(
                    deliver("payment.created", createdPayload(providerTxn)),
                    deliver("payment.authorized", authorizedPayload(providerTxn)),
                    deliver("payment.captured", capturedPayload(providerTxn)),
                    deliver("settlement.paid",
                            settlementPayload(settlementId, providerTxn, today())));

            forceClaimOrder(events, permutation);
            drainUntilQuiet();

            String where = "ordering " + names(permutation, events);
            assertConverged(paymentId, providerTxn, where);
            assertThat(countEvents("FAILED"))
                    .as("%s: an event that cannot be applied is lost money, not a retry", where)
                    .isZero();
            assertThat(terminalEventCount())
                    .as("%s: every event must reach a terminal status", where)
                    .isEqualTo(events.size());
            assertThat(ledgerTransactions("PAYMENT_CAPTURE"))
                    .as("%s: capture is one business fact and posts once", where)
                    .isEqualTo(1);
            assertThat(ledgerTransactions("SETTLEMENT_RECEIVED"))
                    .as("%s: a settlement is one business fact however many lines it covers", where)
                    .isEqualTo(1);
            assertLedgerBalanced(where);
            assertReconciliationAgrees(where);
        }
    }

    // ------------------------------------------------------------------ C-ORD-02

    /**
     * The same lifecycle, but with nothing but our own {@code POST /payments} ahead of it: every
     * transition arrives as a webhook, so the first event applied may be the third one the provider
     * emitted.
     *
     * <p>This is the ordering spec 03 §B5 calls the <b>apply-and-flag</b> case — "transition not
     * legal and the payment is behind: apply it anyway, and audit the anomaly". It is also the
     * ordering the deterministic suite cannot reach, because {@code ProviderWebhookIT} authorises
     * and captures through the API before it sends a single event.
     */
    @Test
    @DisplayName("C-ORD-02: all 6 orderings of a webhook-only lifecycle converge, even though the "
            + "first event applied is often not the first one emitted")
    void everyOrderingOfAWebhookOnlyLifecycleConverges() throws Exception {
        for (List<Integer> permutation : permutations(3)) {
            clearDatabase();
            String providerTxn = "psp_" + unique();
            String paymentId = createPayment(providerTxn);
            String settlementId = "sset_" + unique();

            List<String> events = List.of(
                    deliver("payment.authorized", authorizedPayload(providerTxn)),
                    deliver("payment.captured", capturedPayload(providerTxn)),
                    deliver("settlement.paid",
                            settlementPayload(settlementId, providerTxn, today())));

            forceClaimOrder(events, permutation);
            drainUntilQuiet();

            String where = "ordering " + names(permutation, events);
            assertConverged(paymentId, providerTxn, where);
            assertThat(countEvents("FAILED"))
                    .as("%s: a lifecycle delivered out of order must not exhaust its retries", where)
                    .isZero();
            assertThat(terminalEventCount())
                    .as("%s: every event must reach a terminal status", where)
                    .isEqualTo(events.size());
            assertThat(ledgerTransactions("PAYMENT_CAPTURE"))
                    .as("%s: capture is one business fact and posts once", where)
                    .isEqualTo(1);
            assertThat(ledgerTransactions("SETTLEMENT_RECEIVED")).isEqualTo(1);
            assertLedgerBalanced(where);
            assertReconciliationAgrees(where);
        }
    }

    // ------------------------------------------------------------------ C-ORD-03

    /**
     * A refund racing its own settlement.
     *
     * <p>Whichever way the race goes, both orderings are legitimate and they are <i>different</i>:
     * a refund before settlement is an exact reversal of the capture, a refund after it moves
     * {@code PLATFORM_CASH}. Asserting a fixed ledger type here would be asserting one race won. What
     * is invariant is the terminal state, and that exactly one refund posting exists — two would be
     * money returned twice.
     */
    @Test
    @DisplayName("C-ORD-03: all 6 orderings of captured/settlement/refunded end REFUNDED with "
            + "exactly one refund posting, and every ordering reconciles to the same outcome")
    void everyOrderingOfARefundRacingItsSettlementEndsRefunded() throws Exception {
        List<String> outcomesSeen = new ArrayList<>();

        for (List<Integer> permutation : permutations(3)) {
            clearDatabase();
            String providerTxn = "psp_" + unique();
            String paymentId = createCapturedPayment(providerTxn);
            String settlementId = "sset_" + unique();

            List<String> events = List.of(
                    deliver("payment.captured", capturedPayload(providerTxn)),
                    deliver("settlement.paid",
                            settlementPayload(settlementId, providerTxn, today())),
                    deliver("payment.refunded", refundedPayload(providerTxn)));

            forceClaimOrder(events, permutation);
            drainUntilQuiet();

            String where = "ordering " + names(permutation, events);
            assertThat(stateOf(paymentId))
                    .as("%s: REFUNDED is terminal, so it is where every ordering must converge", where)
                    .isEqualTo("REFUNDED");
            assertThat(providerStatusOf(providerTxn))
                    .as("%s: and the provider's own row must say so too — before defect 22 a "
                            + "settlement applied after a refund dragged it back to SETTLED", where)
                    .isEqualTo("REFUNDED");
            assertThat(ledgerTransactions("REVERSAL") + ledgerTransactions("PAYMENT_REFUND_SETTLED"))
                    .as("%s: the money goes back exactly once, whichever ledger story it tells", where)
                    .isEqualTo(1);
            assertThat(countEvents("FAILED"))
                    .as("%s: a refund is never a retryable event", where)
                    .isZero();
            assertLedgerBalanced(where);

            // Reconciliation is *not* expected to call this payment clean, and it would be
            // dishonest to assert that it does: the expected side is derived from the capture
            // posting, which a reversal cancels, so a refund is reported as a discrepancy for a
            // human by design (spec 04, C-RECON-03). What this file owns is that the answer does
            // not depend on the order the events arrived in — before defect 22 it did, because a
            // refund-first ordering and a settlement-first ordering disagreed about the provider's
            // own status.
            outcomesSeen.add(outcomeFor(providerTxn));
        }

        assertThat(outcomesSeen.stream().distinct().toList())
                .as("the batch's verdict on this transaction must not depend on arrival order")
                .hasSize(1);
    }

    // ------------------------------------------------------------------ C-ORD-04

    /**
     * The seed this run used, and the command that reproduces it.
     *
     * <p>Prefer <b>reproducible</b> over <b>novel</b> for the default, and get novelty from the
     * schedule instead. A randomised test that cannot be replayed is a source of flaky builds: a
     * bug on 1 ordering in 50 turns CI red today and green tomorrow, and the tempting fix is to
     * re-run. So CI passes the commit SHA, which means the same commit always explores the same
     * orderings forever while every new commit explores something new, and a nightly soak rotates
     * through fresh seeds. Locally there is no SHA, so the clock is used and the run is novel —
     * and the seed is printed either way, because a random test that cannot name its own seed is
     * a coin flip with extra steps.
     */
    private static long seed() {
        String supplied = System.getProperty("reconcile.ordering.seed");
        if (supplied == null || supplied.isBlank()) {
            return System.nanoTime();
        }
        // Hashed rather than parsed. `Long.getLong` would reject a git SHA such as "f969294" as
        // malformed and fall back to the clock - silently reintroducing the exact
        // non-determinism this property exists to remove. Anything that can be a string can be a
        // seed: a commit SHA, a date, a number.
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(supplied.getBytes(StandardCharsets.UTF_8));
            return java.nio.ByteBuffer.wrap(digest).getLong();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            // SHA-256 is required of every JVM. If this ever fires, the platform is not Java.
            throw new IllegalStateException(impossible);
        }
    }

    /** A short digest of the orderings explored, so two runs on one seed can be compared. */
    private static String digestOf(List<String> explored) {
        List<String> sorted = new ArrayList<>(explored);
        Collections.sort(sorted);
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(String.join("|", sorted).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                out.append(String.format("%02x", hash[i]));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** What to print and what to replay with, so a failure names its own reproduction. */
    private static String describeSeed() {
        String supplied = System.getProperty("reconcile.ordering.seed");
        return (supplied == null || supplied.isBlank() ? "(clock)" : supplied)
                + " — replay with -Dreconcile.ordering.seed=" + supplied;
    }

    /**
     * Sixteen payments, sixty-four events, sixteen senders released together, eight workers
     * draining — and nothing anywhere telling the system what order to use.
     *
     * <p>The seed is printed and overridable with {@code -Dreconcile.ordering.seed}, so a failure
     * here is reproducible rather than a ghost. It matters more here than anywhere else in the
     * suite: a randomized test that cannot be replayed is a source of flaky builds, which is the one
     * thing a green suite must not be. CI passes the commit SHA — the same commit always explores
     * the same orderings, and every new commit explores new ones — while a scheduled soak rotates
     * the seed so coverage keeps moving without the gate ever becoming unrepeatable.
     */
    @Test
    @DisplayName("C-ORD-04: 64 events delivered concurrently in 16 threads, drained by 8 workers, "
            + "reconcile to agreement on all 16 payments")
    void concurrentDeliveryAndConcurrentDrainingConverge() throws Exception {
        long seed = seed();
        Random random = new Random(seed);
        String where = "seed " + describeSeed();

        List<String> providerTxns = new ArrayList<>();
        Map<String, String> paymentsByProviderTxn = new LinkedHashMap<>();
        // The orderings this run actually produced, so "reproducible" is a claim about the
        // behaviour and not only about the seed string being printed. Two runs on one seed must
        // print the same digest; that is what makes a red build replayable rather than merely
        // re-describable.
        List<String> explored = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < FANOUT; i++) {
            String providerTxn = "psp_" + unique();
            providerTxns.add(providerTxn);
            paymentsByProviderTxn.put(providerTxn, createPayment(providerTxn));
        }

        // Each thread owns one payment and delivers its four events in its own random order, so
        // arrival order is genuinely unplanned rather than shuffled on one thread.
        ExecutorService senders = Executors.newFixedThreadPool(FANOUT);
        CyclicBarrier startLine = new CyclicBarrier(FANOUT);
        List<Callable<Void>> deliveries = new ArrayList<>();
        for (int i = 0; i < FANOUT; i++) {
            String providerTxn = providerTxns.get(i);
            String settlementId = "sset_" + unique();
            List<String> types = new ArrayList<>(List.of(
                    "payment.authorized", "payment.captured", "payment.authorized",
                    "settlement.paid"));
            Collections.shuffle(types, random);
            Map<String, String> payloads = Map.of(
                    "payment.created", createdPayload(providerTxn),
                    "payment.authorized", authorizedPayload(providerTxn),
                    "payment.captured", capturedPayload(providerTxn),
                    "settlement.paid", settlementPayload(settlementId, providerTxn, today()));
            List<String> order = new ArrayList<>(types);
            // Repeat the first two so every payment gets a superseded pair as well as a live
            // lifecycle: late duplicates must be no-ops, whichever way they interleave.
            order.addAll(List.of("payment.created", "payment.captured"));

            deliveries.add(() -> {
                startLine.await();
                explored.add(String.join(">", order));
                for (String type : order) {
                    send("evt-" + unique(), payloads.get(type), currentTs());
                }
                return null;
            });
        }

        for (Future<Void> delivered : senders.invokeAll(deliveries)) {
            delivered.get();
        }
        senders.shutdown();

        // Printed unconditionally, not only on failure, and after the orderings exist so it can
        // say which ones. A soak's value is the ordering nobody has tried yet, and a green run
        // that does not record what it explored cannot be referred back to — so the passing result
        // is the one that most needs recording. Two runs on one seed print the same digest, which
        // is what makes a red build replayable rather than merely re-describable.
        System.out.println("[ordering] C-ORD-04 " + where + " explored "
                + digestOf(explored) + " across " + explored.size() + " payment(s)");

        drainUntilQuiet();

        assertThat(countEvents("FAILED"))
                .as("%s: nothing failed, so every permutation of this burst is applicable", where)
                .isZero();
        assertThat(strandedEvents())
                .as("%s: nothing left unapplied — a stranded event is silent data loss", where)
                .isEmpty();

        for (String providerTxn : providerTxns) {
            assertConverged(paymentsByProviderTxn.get(providerTxn), providerTxn, where);
        }

        assertThat(ledgerTransactions("PAYMENT_CAPTURE"))
                .as("%s: sixteen payments, sixteen captures", where)
                .isEqualTo(FANOUT);
        assertThat(ledgerTransactions("SETTLEMENT_RECEIVED"))
                .as("%s: and sixteen settlements, whatever order they were applied in", where)
                .isEqualTo(FANOUT);
        assertLedgerBalanced(where);

        String batchId = runBatch();
        assertThat(countResults(batchId, "MATCHED"))
                .as("%s: all sixteen payments must agree on both sides", where)
                .isEqualTo(FANOUT);
        assertThat(countResults(batchId, "STATUS_MISMATCH"))
                .as("%s: a status disagreement here is our own ingestion order talking", where)
                .isZero();
        assertThat(openCases())
                .as("%s: and no false discrepancy was raised against a correctly settled payment",
                        where)
                .isZero();
    }

    // ------------------------------------------------------------- convergence

    /**
     * The one invariant every ordering has to satisfy, checked the way an auditor would check it:
     * both sides of the payment say the same thing, and it is the state the provider's account
     * implies.
     */
    private void assertConverged(String paymentId, String providerTxn, String where) {
        assertThat(stateOf(paymentId))
                .as("%s: payment %s", where, paymentId)
                .isEqualTo("SETTLED");
        assertThat(providerStatusOf(providerTxn))
                .as("%s: provider row for %s", where, providerTxn)
                .isEqualTo("SETTLED");
    }

    /** L1: nothing half-written, and no money sitting in a posting that failed. */
    private void assertLedgerBalanced(String where) {
        assertThat(sql.sql("SELECT count(*) AS n FROM ledger_transactions WHERE state <> 'POSTED'")
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0))
                .as("%s: L1 — every posting completes or none does", where)
                .isZero();
    }

    /** Both sides agree, so the batch opens no case. A case here is a false discrepancy. */
    private void assertReconciliationAgrees(String where) {
        String batchId = runBatch();
        assertThat(countResults(batchId, "STATUS_MISMATCH"))
                .as("%s: the provider row must not lag the payment we already advanced", where)
                .isZero();
        assertThat(countResults(batchId, "MISSING_ON_PROVIDER"))
                .as("%s: the provider's statement of every transaction must exist", where)
                .isZero();
        assertThat(countResults(batchId, "MATCHED"))
                .as("%s: a settled payment that agrees on both sides is MATCHED", where)
                .isPositive();
        assertThat(openCases())
                .as("%s: reconciliation exists to find real disagreements, not to invent them", where)
                .isZero();
    }

    // ------------------------------------------------------------------ drain

    /**
     * Drains the inbox until nothing is left unapplied.
     *
     * <p>Repeated rounds rather than one, and that is not papering over a race. A worker that
     * claims nothing while a peer is still applying its batch is entitled to stop — and if that
     * peer then defers an event, the event is back in the queue with nobody left to pick it up. In
     * production the scheduled poller picks it up two seconds later; here the next round is that
     * poller. Bounded, so a genuine stuck event fails the test rather than looping.
     */
    private void drainUntilQuiet() throws Exception {
        long deadline = System.nanoTime() + DRAIN_BUDGET_MILLIS * 1_000_000L;
        int round = 0;
        while (round < ROUNDS || System.nanoTime() < deadline) {
            drainOnce();
            round++;
            if (strandedEvents().isEmpty()) {
                return;
            }
            // Nothing is claimable until a deferred event's backoff elapses, so spinning harder
            // than the retry schedule cannot help. Sleeping here is not a race-manufacturing
            // sleep - §8 rules out Thread.sleep for *creating* a race, and this waits one out.
            if (System.nanoTime() < deadline && !anythingClaimable()) {
                Thread.sleep(100);
            }
        }
        throw new AssertionError("the inbox never went quiet after " + round + " drain rounds: "
                + strandedEvents());
    }

    /** Whether any stranded event is actually due for a claim right now. */
    private boolean anythingClaimable() {
        return sql.sql("""
                SELECT count(*) AS n FROM provider_events
                 WHERE status IN ('PENDING','FAILED') AND next_attempt_at <= now()
                """).optional((rs, rowNum) -> rs.getInt("n")).orElse(0) > 0;
    }

    /**
     * Drains the inbox with {@link #WORKERS} threads released together, until nothing is claimable.
     *
     * <p>Concurrent because that is the deployment: several instances, each with its own poll. A
     * single-threaded drain would serialise the claim query's {@code FOR UPDATE SKIP LOCKED} and
     * hide the split-batch behaviour that makes out-of-order application possible at all.
     */
    private void drainOnce() throws Exception {
        ExecutorService drainers = Executors.newFixedThreadPool(WORKERS);
        CyclicBarrier startLine = new CyclicBarrier(WORKERS);
        List<Callable<Integer>> claims = new ArrayList<>();
        for (int i = 0; i < WORKERS; i++) {
            claims.add(() -> {
                startLine.await();
                int total = 0;
                int claimed;
                do {
                    claimed = worker.processBatch(50);
                    total += claimed;
                } while (claimed > 0);
                return total;
            });
        }
        for (Future<Integer> drained : drainers.invokeAll(claims)) {
            drained.get();
        }
        drainers.shutdown();
    }

    // ------------------------------------------------------- ordering control

    /**
     * Makes the worker apply {@code events} in exactly the order given.
     *
     * <p>{@code next_attempt_at} is the claim query's sort key, so rewriting it reproduces a
     * permutation exactly. Randomising the schedule and hoping for a collision would be a flaky test;
     * this is the same interleaving, guaranteed, and a failure names the ordering that produced it.
     */
    private void forceClaimOrder(List<String> eventIds, List<Integer> permutation) {
        // Earlier timestamp == claimed earlier, so the first element of the permutation is applied
        // last. The worker applies whatever it claims first, which is the tail.
        for (int rank = 0; rank < permutation.size(); rank++) {
            String eventId = eventIds.get(permutation.get(rank));
            sql.sql("UPDATE provider_events SET next_attempt_at = now() - (? * interval '1 second') "
                            + "WHERE event_id = ?")
                    .params(permutation.size() - rank + 1, eventId)
                    .update();
        }
    }

    /** Every ordering of {@code n} distinct items. Complete, not sampled. */
    private static List<List<Integer>> permutations(int n) {
        List<List<Integer>> all = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            current.add(i);
        }
        permute(current, 0, all);
        return all;
    }

    private static void permute(List<Integer> items, int from, List<List<Integer>> all) {
        if (from == items.size()) {
            all.add(List.copyOf(items));
            return;
        }
        for (int i = from; i < items.size(); i++) {
            Collections.swap(items, from, i);
            permute(items, from + 1, all);
            Collections.swap(items, from, i);
        }
    }

    /**
     * A readable label for a failure: which event was applied when.
     *
     * <p>In application order. {@link #forceClaimOrder} gives {@code permutation[0]} the earliest
     * {@code next_attempt_at} and so the first claim, which means the permutation itself already
     * reads first-to-last. An earlier version iterated the permutation backwards and so printed the
     * label in exactly the opposite order to what happened - which is worse than no label, because
     * it sends whoever is diagnosing the failure to the wrong sequence. When this was found, the
     * reported ordering was the reverse of the real one and cost the diagnosis a wrong hypothesis.
     */
    private String names(List<Integer> permutation, List<String> eventIds) {
        List<String> applied = new ArrayList<>();
        for (int index : permutation) {
            applied.add(typeOf(eventIds.get(index)));
        }
        return String.join(" then ", applied);
    }

    // ------------------------------------------------------------- delivery

    private String deliver(String type, String payload) throws Exception {
        String eventId = "evt-" + unique();
        HttpResponse<String> response = send(eventId, payload, currentTs());
        assertThat(response.statusCode())
                .as("delivery of %s was rejected before it could be ordered", type)
                .isEqualTo(202);
        return eventId;
    }

    private HttpResponse<String> send(String eventId, String body, String timestamp)
            throws Exception {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/provider/webhooks"))
                .header("Content-Type", "application/json")
                .header("X-Provider-Event-Id", eventId)
                .header("X-Provider-Timestamp", timestamp)
                .header("X-Provider-Signature",
                        WebhookSignature.sign(SECRET, timestamp, eventId, body.getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String currentTs() {
        return Long.toString(clock.instant().getEpochSecond());
    }

    private String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    private String unique() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    // ------------------------------------------------------------- payloads

    private String createdPayload(String providerTxn) {
        return paymentPayload("payment.created", providerTxn, "CREATED");
    }

    private String authorizedPayload(String providerTxn) {
        return paymentPayload("payment.authorized", providerTxn, "AUTHORIZED");
    }

    private String capturedPayload(String providerTxn) {
        return paymentPayload("payment.captured", providerTxn, "CAPTURED");
    }

    private String refundedPayload(String providerTxn) {
        return paymentPayload("payment.refunded", providerTxn, "REFUNDED");
    }

    private String paymentPayload(String type, String providerTxn, String status) {
        return """
                {"type":"%s","occurredAt":"%s","providerTransactionId":"%s",\
                "merchantReference":"MERCH-1","gross":{"amountMinor":10000,"currency":"EGP"},\
                "net":{"amountMinor":9700,"currency":"EGP"},"status":"%s"}"""
                .formatted(type, clock.instant().atOffset(ZoneOffset.UTC).toString(),
                        providerTxn, status);
    }

    private String settlementPayload(String settlementId, String providerTxn, String date) {
        return """
                {"type":"settlement.paid","occurredAt":"%s","settlement":\
                {"providerSettlementId":"%s","settlementDate":"%s","currency":"EGP",\
                "grossAmountMinor":10000,"feeAmountMinor":300,"netAmountMinor":9700,\
                "lines":[{"providerTransactionId":"%s","grossAmountMinor":10000,"netAmountMinor":9700}]}}"""
                .formatted(clock.instant().atOffset(ZoneOffset.UTC).toString(), settlementId, date,
                        providerTxn);
    }

    // ----------------------------------------------------------------- setup

    private String createPayment(String providerTransactionId) {
        String body = """
                {"merchantReference":"MERCH-1","amount":{"amountMinor":10000,"currency":"EGP"},\
                "provider":"SIMULATED_PSP","providerTransactionId":"%s"}"""
                .formatted(providerTransactionId);
        return jsonString(api("POST", "/api/v1/payments", body), "id");
    }

    private String createCapturedPayment(String providerTransactionId) {
        String paymentId = createPayment(providerTransactionId);
        api("POST", "/api/v1/payments/" + paymentId + "/authorize", "{}");
        api("POST", "/api/v1/payments/" + paymentId + "/capture", "{}");
        return paymentId;
    }

    private String api(String method, String path, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + path))
                    .header("Authorization", "Bearer " + OPERATOR)
                    .header("Idempotency-Key", "ord-" + unique())
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException(
                        method + " " + path + " -> " + response.statusCode() + " " + response.body());
            }
            return response.body();
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private String jsonString(String json, String field) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("no field " + field + " in " + json);
        }
        return matcher.group(1);
    }

    // ------------------------------------------------------------- queries

    private String stateOf(String paymentId) {
        return sql.sql("SELECT state FROM payments WHERE id = ?")
                .param(paymentId)
                .optional((rs, rowNum) -> rs.getString("state")).orElse(null);
    }

    private String providerStatusOf(String providerTxn) {
        return sql.sql("SELECT provider_status FROM provider_transactions "
                        + "WHERE provider = 'SIMULATED_PSP' AND provider_transaction_id = ?")
                .param(providerTxn)
                .optional((rs, rowNum) -> rs.getString("provider_status")).orElse(null);
    }

    private String typeOf(String eventId) {
        return sql.sql("SELECT event_type FROM provider_events WHERE event_id = ?")
                .param(eventId)
                .optional((rs, rowNum) -> rs.getString("event_type")).orElse("?");
    }

    private int countEvents(String status) {
        return sql.sql("SELECT count(*) AS n FROM provider_events WHERE status = ?")
                .param(status)
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }

    private int pendingEventCount() {
        return strandedEvents().size();
    }

    /**
     * Events that are neither applied nor dead, named.
     *
     * <p>Names rather than a count because "3" sends nobody anywhere. Each entry says which event,
     * which type and what the worker last thought was wrong with it, which is the difference
     * between a diagnosis and a puzzle.
     */
    private List<String> strandedEvents() {
        return sql.sql("SELECT event_id, event_type, status, attempts, deferred_attempts, last_error "
                        + "FROM provider_events WHERE status NOT IN ('PROCESSED','NO_EFFECT','FAILED') "
                        + "ORDER BY created_at, event_id")
                .list((rs, rowNum) -> rs.getString("event_type") + "/" + rs.getString("event_id")
                        + " [" + rs.getString("status") + " attempts=" + rs.getInt("attempts")
                        + " deferred=" + rs.getInt("deferred_attempts")
                        + (rs.getString("last_error") == null ? "" : " last_error=" + rs.getString("last_error"))
                        + "]");
    }

    /** What a batch concluded about one transaction, for the order-independence comparison. */
    private String outcomeFor(String providerTxn) {
        return sql.sql("SELECT outcome FROM reconciliation_results WHERE subject_key = ? "
                        + "ORDER BY id LIMIT 1")
                .param("SIMULATED_PSP:" + providerTxn)
                .optional((rs, rowNum) -> rs.getString("outcome"))
                .orElse("NO_RESULT");
    }

    private int terminalEventCount() {
        return sql.sql("SELECT count(*) AS n FROM provider_events "
                        + "WHERE status IN ('PROCESSED','NO_EFFECT')")
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }

    private int ledgerTransactions(String type) {
        return sql.sql("SELECT count(*) AS n FROM ledger_transactions WHERE type = ?")
                .param(type)
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }

    private String runBatch() {
        Instant end = Instant.now().plusSeconds(3600);
        String batchId = reconciliation.createBatch(new ReconciliationService.BatchRequest(
                "SIMULATED_PSP", end.minusSeconds(86_400 * 30), end,
                ReconciliationService.SubjectMode.BOTH, "ordering-test", "req_ordering"));
        batches.runBatch(batchId);
        return batchId;
    }

    private int countResults(String batchId, String outcome) {
        return sql.sql("SELECT count(*) AS n FROM reconciliation_results "
                        + "WHERE batch_id = ? AND outcome = ?")
                .params(batchId, outcome)
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }

    

    private int openCases() {
        return sql.sql("SELECT count(*) AS n FROM reconciliation_cases "
                        + "WHERE status IN ('OPEN','INVESTIGATING')")
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }
}
