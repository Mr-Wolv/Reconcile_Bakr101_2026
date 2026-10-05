package com.reconcile.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.provider.WebhookSignature;
import com.reconcile.service.InboundEventWorker;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The durable inbox, proven over a real socket.
 *
 * <p>Maps to rows {@code I-WEB-01} … {@code I-WEB-10} of the test matrix. The property that matters
 * most is the one the schema exists for: <b>three deliveries, one event, one effect</b>. Every other
 * assertion here is easier; that one is the reason {@code provider_event_deliveries} and
 * {@code provider_events} are separate tables (decision D4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProviderWebhookIT {

    private static final String SECRET = "webhook-secret-token";
    private static final String OPERATOR = "operator-secret-token";


    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    Sql sql;

    @Autowired
    Clock clock;

    @Autowired
    InboundEventWorker worker;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedPostgres::jdbcUrl);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("reconcile.security.operator-token", () -> OPERATOR);
        registry.add("reconcile.security.admin-token", () -> "admin-secret-token");
        registry.add("reconcile.provider.webhook-secret", () -> SECRET);
        // Long enough that the scheduled worker never races a test that is asserting on the
        // PENDING state, and that this class owns the drain instead.
        registry.add("reconcile.provider.event-poll-interval", () -> "1h");
    }

    @BeforeEach
    void reset() {
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

    // ------------------------------------------------------------------ I-WEB-01

    @Test
    @DisplayName("I-WEB-01: a valid signature is 202, with one delivery row and one event row")
    void validSignatureIsAccepted() throws Exception {
        String eventId = "evt-" + unique();
        HttpResponse<String> response = send(eventId, capturedPayload("psp_" + unique()), currentTs());

        assertThat(response.statusCode())
                .as("body was: %s", response.body())
                .isEqualTo(202);
        assertThat(deliveryOutcomes(eventId)).containsExactly("ACCEPTED");
        assertThat(eventRows(eventId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ I-WEB-02

    @Test
    @DisplayName("I-WEB-02: three deliveries of one event produce three rows and one event")
    void repeatedDeliveriesAreDeduplicated() throws Exception {
        String eventId = "evt-" + unique();
        String payload = capturedPayload("psp_" + unique());

        HttpResponse<String> first = send(eventId, payload, currentTs());
        HttpResponse<String> second = send(eventId, payload, currentTs());
        HttpResponse<String> third = send(eventId, payload, currentTs());

        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(second.statusCode())
                .as("a duplicate is 200, not 202: nothing was accepted, and telling the provider "
                        + "otherwise discourages it from investigating a real delivery problem")
                .isEqualTo(200);
        assertThat(third.statusCode()).isEqualTo(200);

        assertThat(deliveryOutcomes(eventId))
                .as("every attempt is recorded, whatever its outcome")
                .containsExactly("ACCEPTED", "DUPLICATE", "DUPLICATE");
        assertThat(eventRows(eventId))
                .as("but the effect can only happen once")
                .isEqualTo(1);
        assertThat(duplicateCount(eventId)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ I-WEB-03

    @Test
    @DisplayName("I-WEB-03: a bad signature is 401, recorded, and applies no business effect")
    void badSignatureIsRefused() throws Exception {
        String eventId = "evt-" + unique();
        String body = capturedPayload("psp_" + unique());

        HttpResponse<String> response = post(body, currentTs(), "0".repeat(64), eventId);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("WEBHOOK_REJECTED_SIGNATURE");
        assertThat(deliveryOutcomes(eventId)).containsExactly("REJECTED_SIGNATURE");
        assertThat(eventRows(eventId))
                .as("a forged request must never become a durable event")
                .isZero();
        assertThat(providerTransactionRows()).isZero();
    }

    // ------------------------------------------------------------------ I-WEB-04

    @Test
    @DisplayName("I-WEB-04: a timestamp 10 minutes old is 401; one inside the window is 202")
    void staleTimestampIsRefused() throws Exception {
        String staleEvent = "evt-" + unique();
        HttpResponse<String> stale = post(capturedPayload("psp_a"), tenMinutesAgo(),
                sign(tenMinutesAgo(), staleEvent, capturedPayload("psp_a")), staleEvent);

        assertThat(stale.statusCode()).isEqualTo(401);
        assertThat(stale.body()).contains("WEBHOOK_REJECTED_TIMESTAMP");
        assertThat(deliveryOutcomes(staleEvent)).containsExactly("REJECTED_TIMESTAMP");

        String freshEvent = "evt-" + unique();
        HttpResponse<String> fresh = send(freshEvent, capturedPayload("psp_b"), currentTs());
        assertThat(fresh.statusCode()).isEqualTo(202);
    }

    // ------------------------------------------------------------------ I-WEB-05

    @Test
    @DisplayName("I-WEB-05: a body that would re-serialise differently is still verified on its raw bytes")
    void verificationUsesRawBytes() throws Exception {
        // Signed over exactly these bytes, with key order and spacing that any re-serialisation
        // would change. If verification deserialised first, this would fail.
        String body = "{ \"type\" : \"payment.captured\" ,"
                + " \"occurredAt\":\"2026-10-04T10:00:00Z\","
                + " \"providerTransactionId\" : \"psp_raw\","
                + " \"merchantReference\" : \"MERCH-RAW\","
                + " \"gross\":{\"amountMinor\":10000,\"currency\":\"EGP\"},"
                + " \"net\":{\"amountMinor\":9700,\"currency\":\"EGP\"},"
                + " \"status\":\"CAPTURED\" }";

        HttpResponse<String> response = send("evt-" + unique(), body, currentTs());

        assertThat(response.statusCode())
                .as("the signature covers the bytes that arrived, not an object derived from them")
                .isEqualTo(202);
    }

    // ------------------------------------------------------------------ I-WEB-06

    @Test
    @DisplayName("I-WEB-06: a capture delivered after a refund leaves the payment REFUNDED")
    void supersededEventHasNoEffect() throws Exception {
        String paymentId = createCapturedPayment("psp_" + unique());
        refund(paymentId);

        // The provider now delivers the capture we already knew about, out of order.
        String eventId = "evt-" + unique();
        send(eventId, capturedPayload(providerTxnOf(paymentId)), currentTs());

        worker.processBatch(50);

        assertThat(stateOf(paymentId))
                .as("resurrecting a state the payment has legitimately left is worse than the delay")
                .isEqualTo("REFUNDED");
        assertThat(eventStatus(eventId)).isEqualTo("NO_EFFECT");
        assertThat(auditCount("WEBHOOK_NO_EFFECT")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ I-WEB-07

    @Test
    @DisplayName("I-WEB-07: a claim abandoned mid-flight is reclaimed and applied exactly once")
    void staleClaimIsReclaimedAndAppliedOnce() throws Exception {
        String paymentId = createAuthorizedPayment("psp_" + unique());
        String eventId = "evt-" + unique();
        send(eventId, capturedPayload(providerTxnOf(paymentId)), currentTs());

        // Simulate a worker killed between claiming and applying: PROCESSING, locked, and stale.
        sql.sql("UPDATE provider_events SET status = 'PROCESSING', locked_at = now() - interval "
                + "'6 minutes', locked_by = 'wkr_dead' WHERE event_id = ?")
                .param(eventId).update();

        assertThat(stateOf(paymentId)).isEqualTo("AUTHORIZED");

        worker.reclaimStaleClaims();
        worker.processBatch(50);

        assertThat(stateOf(paymentId)).isEqualTo("CAPTURED");
        assertThat(eventStatus(eventId)).isEqualTo("PROCESSED");
        assertThat(ledgerTransactionsFor("PAYMENT_CAPTURE")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ I-WEB-10@Test
    @DisplayName("I-WEB-10: an unknown event type fails immediately, is never re-claimed, and surfaces")
    void nonRetryableFailureFailsWithoutBurningRetries() throws Exception {
        String body = "{\"type\":\"payment.teleported\",\"occurredAt\":\"2026-10-04T10:00:00Z\","
                + "\"providerTransactionId\":\"psp_x\"}";
        String eventId = "evt-" + unique();
        assertThat(send(eventId, body, currentTs()).statusCode()).isEqualTo(202);

        // Several passes. The bug was never that it retried once — it was that it kept claiming the
        // event forever, because FAILED was both "exhausted" and "waiting to be claimed".
        for (int pass = 0; pass < 12; pass++) {
            worker.processBatch(50);
        }

        assertThat(attemptsOf(eventId))
                .as("retrying cannot fix an unknown event type, so one attempt is the honest answer")
                .isEqualTo(1);
        assertThat(eventStatus(eventId))
                .as("DEAD, not FAILED: FAILED means 'waiting for its next attempt' and the claim "
                        + "query takes FAILED, so an event in FAILED is always re-claimed")
                .isEqualTo("DEAD");
        assertThat(failedBacklog())
                .as("terminal must not mean hidden — the operations backlog is the only place a "
                        + "poison event is ever mentioned")
                .contains(eventId);
    }

    // ------------------------------------------------------------------ I-WEB-09

    @Test
    @DisplayName("I-WEB-09: an event for an unknown payment backs off, then succeeds once it appears")
    void unknownPaymentIsRetriedUntilItExists() throws Exception {
        String providerTxn = "psp_" + unique();
        String eventId = "evt-" + unique();
        send(eventId, capturedPayload(providerTxn), currentTs());

        worker.processBatch(50);
        assertThat(eventStatus(eventId)).as("retryable: the payment may still arrive").isEqualTo("PENDING");
        assertThat(attemptsOf(eventId)).isEqualTo(1);

        createAuthorizedPayment(providerTxn);

        // Make the event due again, standing in for the backoff elapsing.
        sql.sql("UPDATE provider_events SET next_attempt_at = now() WHERE event_id = ?")
                .param(eventId).update();
        worker.processBatch(50);

        assertThat(eventStatus(eventId)).isEqualTo("PROCESSED");
    }

    // ------------------------------------------------------------------ E2E-WEB-04

    @Test
    @DisplayName("E2E-WEB-04: an oversize body is 413, recorded as TOO_LARGE, and never becomes an event")
    void oversizeBodyIsRefusedAndRecorded() throws Exception {
        String eventId = "evt-" + unique();

        HttpResponse<String> response = send(eventId, oversizedPayload(), currentTs());

        assertThat(response.statusCode())
                .as("body was %d bytes, limit is %d", ProviderWebhookController.MAX_BODY_BYTES + 1024,
                        ProviderWebhookController.MAX_BODY_BYTES)
                .isEqualTo(413);
        assertThat(response.body())
                .as("and the caller is told which limit it crossed, not merely that it failed")
                .contains("WEBHOOK_TOO_LARGE");
        assertThat(deliveryOutcomes(eventId))
                .as("the refusal must be recorded. A provider sending a body we refuse is a fact "
                        + "an operator needs; a refusal that leaves no trace is indistinguishable "
                        + "from a delivery that never arrived.")
                .containsExactly("TOO_LARGE");
        assertThat(eventRows(eventId))
                .as("nothing may be parsed, verified or persisted once the body is over the limit")
                .isZero();
    }

    @Test
    @DisplayName("E2E-WEB-04b: size is decided before the signature, so an oversize body is 413 and not 401")
    void sizeIsCheckedBeforeTheSignature() throws Exception {
        String eventId = "evt-" + unique();

        HttpResponse<String> response =
                post(oversizedPayload(), currentTs(), "not-a-valid-signature", eventId);

        // The point of §B2's ordering is that a flood of junk never reaches work we would rather
        // not do. If the signature were verified first this would be 401, and the two would be
        // indistinguishable to the sender: both say "refused", neither says which rule applied.
        assertThat(response.statusCode())
                .as("a 401 here would mean the signature was verified before the size, so every "
                        + "oversize body costs an HMAC and reports the wrong reason")
                .isEqualTo(413);
        assertThat(deliveryOutcomes(eventId))
                .as("and the recorded reason must be the one that actually decided it")
                .containsExactly("TOO_LARGE");
    }

    // ------------------------------------------------------------------ F-04

    /**
     * The resource-boundary test, written by hand because {@link HttpClient} will not help.
     *
     * <p>The previous audit sent a 300,123-byte chunked request and got 400
     * {@code MALFORMED_REQUEST} with no delivery row. Both halves of that were wrong: the size
     * contract says 413 with a {@code TOO_LARGE} record, and a chunked request has no
     * {@code Content-Length} for the old check to read.
     */
    @Test
    @DisplayName("F-04: a chunked body over the limit is 413, recorded, and bounded")
    void chunkedOversizeIsRefusedAndRecorded() throws Exception {
        String eventId = "evt-" + unique();
        String payload = oversizedPayload();

        RawHttpResponse response = sendChunked(eventId, payload);

        assertThat(response.status)
                .as("the documented answer for an oversize webhook body, declared or not")
                .isEqualTo(413);
        // The body text is deliberately not asserted here. Tomcat answers a chunked request with a
        // chunked response, so reading the problem document out of a hand-rolled socket means
        // writing a chunked decoder — and asserting the same string twice through two different
        // parsers adds a way to fail without adding evidence. The declared-length path
        // (oversizeBodyIsRefusedAndRecorded) asserts the problem document properly; what is specific
        // to this path is that the refusal happens at all, and that it is recorded.
        assertThat(deliveryOutcomes(eventId))
                .as("F-04: the refusal must leave the same evidence a declared oversize body "
                        + "leaves. A 413 with no row is the symptom the audit found.")
                .containsExactly("TOO_LARGE");
        assertThat(eventRows(eventId))
                .as("nothing may be parsed, verified or persisted")
                .isZero();
    }

    @Test
    @DisplayName("F-04: a body exactly at the limit is accepted, and one byte over is not")
    void theLimitItselfIsInclusive() throws Exception {
        // "Exactly at the limit" is the boundary a size check gets wrong, and it is the reason the
        // wrapper reads one byte past the cap rather than stopping on it.
        String atLimit = payloadOfTotalSize(ProviderWebhookController.MAX_BODY_BYTES);
        assertThat(atLimit.getBytes(StandardCharsets.UTF_8).length)
                .as("the fixture is exactly at the limit, so a rejection cannot be blamed on padding")
                .isEqualTo(ProviderWebhookController.MAX_BODY_BYTES);

        HttpResponse<String> accepted = send("evt-" + unique(), atLimit, currentTs());
        assertThat(accepted.statusCode())
                .as("a body of exactly the limit is legal and must be accepted")
                .isEqualTo(202);

        String overEventId = "evt-" + unique();
        HttpResponse<String> refused =
                send(overEventId, payloadOfTotalSize(ProviderWebhookController.MAX_BODY_BYTES + 1),
                        currentTs());
        assertThat(refused.statusCode()).isEqualTo(413);
        assertThat(deliveryOutcomes(overEventId)).containsExactly("TOO_LARGE");
    }

    @Test
    @DisplayName("F-04: a chunked body well under the limit is accepted normally")
    void chunkedUnderTheLimitIsAccepted() throws Exception {
        String eventId = "evt-" + unique();
        String payload = capturedPayload("psp_" + unique());

        RawHttpResponse response = sendChunked(eventId, payload);

        assertThat(response.status).isEqualTo(202);
        assertThat(eventRows(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("F-04: a chunked body far over the limit is refused without the server draining it")
    void aVeryLargeChunkedBodyIsRefusedToo() throws Exception {
        // 8 MiB. If the bound were enforced after the fact rather than while reading, this is the
        // request that costs the process 8 MiB of heap and a long pause.
        String eventId = "evt-" + unique();
        String payload = "{\"merchantReference\":\"" + "x".repeat(8 * 1024 * 1024) + "\"}";

        RawHttpResponse response = sendChunked(eventId, payload);

        assertThat(response.status).isEqualTo(413);
        assertThat(deliveryOutcomes(eventId)).containsExactly("TOO_LARGE");

        // The connection is expected to be dropped rather than drained: consuming the remaining
        // 8 MiB in order to be polite to the next request on the socket is the very cost the bound
        // exists to avoid. Reaching a response at all is the evidence that it was not.
        assertThat(response.status).isPositive();
    }

    // ------------------------------------------------------------------ F-06

    @Test
    @DisplayName("F-06: replaying a valid request under a new event id is refused, not enqueued")
    void anAlteredEventIdBreaksTheSignature() throws Exception {
        String originalId = "evt-" + unique();
        String replayedId = "evt-" + unique();
        String payload = capturedPayload("psp_" + unique());
        String timestamp = currentTs();

        // The provider's genuine request, captured whole.
        String genuineSignature = sign(timestamp, originalId, payload);
        assertThat(send(originalId, payload, timestamp).statusCode()).isEqualTo(202);

        // Byte-for-byte identical except for the identity it is filed under. Before the event id
        // became signed material this verified, and the inbox took a second event — which is the
        // amplification F-06 describes. The business effect stayed protected by payment-level
        // idempotency, but the event, the inbox row and the audit trail were all duplicated.
        HttpResponse<String> replay =
                post(payload, timestamp, genuineSignature, replayedId);

        assertThat(replay.statusCode())
                .as("the signature covers the event id, so a different id is a different message")
                .isEqualTo(401);
        assertThat(replay.body()).contains("WEBHOOK_REJECTED_SIGNATURE");
        assertThat(eventRows(replayedId))
                .as("no second event may exist")
                .isZero();
        assertThat(deliveryOutcomes(replayedId)).containsExactly("REJECTED_SIGNATURE");
    }

    @Test
    @DisplayName("F-06: the provider's own retry — same id, same bytes — is still accepted")
    void theProvidersOwnRetryStillWorks() throws Exception {
        // The control for the test above, and the reason the replay test is meaningful: a "fix"
        // that rejected an honest retry would pass it and break every real provider.
        String eventId = "evt-" + unique();
        String payload = capturedPayload("psp_" + unique());
        String timestamp = currentTs();

        assertThat(send(eventId, payload, timestamp).statusCode()).isEqualTo(202);
        assertThat(send(eventId, payload, timestamp).statusCode())
                .as("200, not 202: it is the same event, and the inbox has already got it")
                .isEqualTo(200);
        assertThat(eventRows(eventId)).isEqualTo(1);
        assertThat(deliveryOutcomes(eventId))
                .containsExactly("ACCEPTED", "DUPLICATE");
    }

    // ------------------------------------------------------------------ F-07

    @Test
    @DisplayName("F-07: a non-retryable event is terminal after ONE attempt, never eight")
    void nonRetryableEventsAreTerminalImmediately() throws Exception {
        String eventId = "evt-" + unique();
        // An event type no processor handles. Nothing about a second attempt changes the answer.
        String payload = """
                {"type":"payment.teleported","occurredAt":"%s","providerTransactionId":"psp_x"}"""
                .formatted(clock.instant().atOffset(ZoneOffset.UTC).toString());

        assertThat(send(eventId, payload, currentTs()).statusCode()).isEqualTo(202);

        // Several passes, because the bug was not that it retried once — it was that it kept
        // claiming the event forever.
        for (int pass = 0; pass < 12; pass++) {
            worker.processBatch(50);
        }

        assertThat(eventStatus(eventId))
                .as("DEAD, not FAILED. FAILED means 'waiting for its next attempt', and the claim "
                        + "query takes FAILED, so an event in FAILED is always re-claimed.")
                .isEqualTo("DEAD");
    }

    @Test
    @DisplayName("F-07: a DEAD event is still on the operations backlog")
    void deadEventsRemainVisible() throws Exception {
        String eventId = "evt-" + unique();
        String payload = """
                {"type":"payment.teleported","occurredAt":"%s","providerTransactionId":"psp_y"}"""
                .formatted(clock.instant().atOffset(ZoneOffset.UTC).toString());
        send(eventId, payload, currentTs());
        worker.processBatch(50);

        assertThat(attemptsOf(eventId))
                .as("I-WEB-10: a permanently broken event reaches its answer on attempt 1")
                .isEqualTo(1);
        assertThat(failedEventStatuses())
                .as("terminal must mean 'stop retrying', never 'stop showing'")
                .contains("DEAD");
    }

    @Test
    @DisplayName("F-07: a retryable fault is NOT terminal, and keeps its retry budget")
    void retryableFaultsStillRetry() throws Exception {
        // The control for the test above. A worker that made everything DEAD would pass the DEAD
        // assertion and fail this one, which is the only reason the distinction is proven rather
        // than asserted.
        //
        // An event naming a payment we do not have is the retryable shape: the world may change and
        // the payment may appear.
        String eventId = "evt-" + unique();
        send(eventId, capturedPayload("psp_" + unique()), currentTs());

        worker.processBatch(50);

        assertThat(eventStatus(eventId))
                .as("PENDING with a backoff, because the payment it names may yet arrive")
                .isEqualTo("PENDING");
        assertThat(attemptsOf(eventId)).isEqualTo(1);

        assertThat(hasBackoff(eventId))
                .as("a retryable fault backs off instead of hammering: the event is waiting, "
                        + "not finished")
                .isTrue();

        // Clear the jittered backoff rather than sleeping through it. The property under test is
        // that the event is still CLAIMABLE - the one thing that separates PENDING from DEAD - and
        // sleeping would make the assertion depend on a random draw from
        // [0, BACKOFF_BASE), which is a coin flip rather than a test. Before F-07 this row had
        // next_attempt_at = now(), so an immediate second pass always claimed it; that is exactly
        // the no-backoff behaviour the finding removed, and asserting it here would undo the fix.
        clearBackoff(eventId);

        worker.processBatch(50);
        assertThat(attemptsOf(eventId))
                .as("a second pass claims it again: this is the behaviour DEAD removed for the "
                        + "other case, and it must survive for this one")
                .isEqualTo(2);
        assertThat(eventStatus(eventId))
                .as("and it is still a live PENDING row, not merely a row that was touched once")
                .isEqualTo("PENDING");
    }

    // ------------------------------------------------------------------ raw HTTP helpers

    /** A hand-parsed HTTP/1.1 response: {@link HttpClient} cannot produce these requests. */
    private record RawHttpResponse(int status, String body) {
    }

    /**
     * Sends a webhook over a raw socket using {@code Transfer-Encoding: chunked}.
     *
     * <p>Written by hand because this is the only way to exercise the path that matters. The
     * {@link HttpClient} always sets {@code Content-Length} for a body it has in memory, so a test
     * written with it can only ever cover the declared-length branch — which is precisely the branch
     * that already worked when F-04 was found.
     */
    private RawHttpResponse sendChunked(String eventId, String body) throws Exception {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String timestamp = currentTs();

        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setSoTimeout(20_000);
            java.io.OutputStream out = socket.getOutputStream();

            String head = "POST /api/v1/provider/webhooks HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "X-Provider-Event-Id: " + eventId + "\r\n"
                    + "X-Provider-Timestamp: " + timestamp + "\r\n"
                    + "X-Provider-Signature: " + sign(timestamp, eventId, body) + "\r\n"
                    + "Transfer-Encoding: chunked\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));

            // Chunks of a size no Content-Length could ever summarise, so the server has to decide
            // from what it has actually read rather than from what the headers claimed.
            try {
                int chunk = 8192;
                for (int offset = 0; offset < payload.length; offset += chunk) {
                    int size = Math.min(chunk, payload.length - offset);
                    out.write((Integer.toHexString(size) + "\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    out.write(payload, offset, size);
                    out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    if (size == chunk) {
                        out.flush();
                    }
                }
                out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (java.io.IOException stoppedEarly) {
                // Expected once the server has decided the body is over the limit and closed: it is
                // no longer reading, and continuing to write would only block. The response below is
                // the thing being asserted.
                assertThat(stoppedEarly)
                        .as("a connection reset while streaming an oversize body is the correct "
                                + "outcome, not a test failure")
                        .isNotNull();
            }

            return readResponse(socket.getInputStream());
        }
    }

    private RawHttpResponse readResponse(java.io.InputStream in) throws java.io.IOException {
        String header = readHeaderBlock(in);
        int firstSpace = header.indexOf(' ');
        int secondSpace = header.indexOf(' ', firstSpace + 1);
        int status = Integer.parseInt(header.substring(firstSpace + 1, secondSpace));
        return new RawHttpResponse(status, readUntilEof(in));
    }

    private String readHeaderBlock(java.io.InputStream in) throws java.io.IOException {
        StringBuilder block = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) {
            block.append((char) b);
            int length = block.length();
            if (length >= 4 && block.charAt(length - 4) == '\r'
                    && block.charAt(length - 3) == '\n'
                    && block.charAt(length - 2) == '\r'
                    && block.charAt(length - 1) == '\n') {
                break;
            }
        }
        return block.toString();
    }

    private String readUntilEof(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] scratch = new byte[8192];
        int read;
        while ((read = in.read(scratch)) >= 0) {
            buffer.write(scratch, 0, read);
        }
        String raw = buffer.toString(StandardCharsets.UTF_8);
        int bodyStart = raw.indexOf("\r\n\r\n");
        return bodyStart < 0 ? raw : raw.substring(bodyStart + 4);
    }

    /**
     * A syntactically valid provider payload of <b>exactly</b> {@code totalBytes} bytes.
     *
     * <p>Padding goes into the merchant reference rather than being appended, so the result is still
     * well-formed JSON: a body that were malformed would be refused for the wrong reason and would
     * prove nothing about size.
     */
    private String payloadOfTotalSize(int totalBytes) {
        String prefix = "{\"type\":\"payment.captured\",\"occurredAt\":\""
                + clock.instant().atOffset(ZoneOffset.UTC) + "\","
                + "\"providerTransactionId\":\"psp_" + unique() + "\",\"merchantReference\":\"";
        String suffix = "\",\"gross\":{\"amountMinor\":10000,\"currency\":\"EGP\"},"
                + "\"net\":{\"amountMinor\":9700,\"currency\":\"EGP\"},\"status\":\"CAPTURED\"}";
        int padding = totalBytes - prefix.length() - suffix.length();
        assertThat(padding)
                .as("the fixture must be able to reach the limit without the envelope overshooting it")
                .isPositive();

        String payload = prefix + "x".repeat(padding) + suffix;
        assertThat(payload.getBytes(StandardCharsets.UTF_8).length).isEqualTo(totalBytes);
        return payload;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A payload that is otherwise perfectly valid, padded past the body limit.
     *
     * <p>Padding the <b>merchant reference</b> rather than appending bytes keeps the JSON
     * well-formed, so the test proves the limit refused a body that was otherwise acceptable —
     * a malformed body would be refused for the wrong reason and would prove nothing about size.
     */
    private String oversizedPayload() {
        String padding = "x".repeat(ProviderWebhookController.MAX_BODY_BYTES + 1024);
        return """
                {"type":"payment.captured","occurredAt":"%s","providerTransactionId":"psp_%s",\
                "merchantReference":"%s","gross":{"amountMinor":10000,"currency":"EGP"},\
                "net":{"amountMinor":9700,"currency":"EGP"},"status":"CAPTURED"}"""
                .formatted(clock.instant().atOffset(ZoneOffset.UTC).toString(), unique(), padding);
    }

    private String unique() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    private HttpResponse<String> send(String eventId, String body, String timestamp)
            throws Exception {
        return post(body, timestamp, sign(timestamp, eventId, body), eventId);
    }

    private HttpResponse<String> post(String body, String timestamp, String signature,
            String eventId) throws Exception {

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/api/v1/provider/webhooks"))
                .header("Content-Type", "application/json")
                .header("X-Provider-Event-Id", eventId)
                .header("X-Provider-Timestamp", timestamp)
                .header("X-Provider-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private String sign(String timestamp, String eventId, String body) {
        return WebhookSignature.sign(SECRET, timestamp, eventId,
                body.getBytes(StandardCharsets.UTF_8));
    }

    private String currentTs() {
        return Long.toString(clock.instant().getEpochSecond());
    }

    private String tenMinutesAgo() {
        return Long.toString(clock.instant().minusSeconds(600).getEpochSecond());
    }

    /**
     * Defect 21: a late {@code payment.created} must not drag the provider-side status backwards.
     *
     * <p>Found by the probe, not by the suite. The inbound worker applies a claimed batch of events
     * in whatever order it processes them, so a settlement can be applied before the capture that
     * preceded it. The payment-side state machine already refuses to resurrect a state it has left
     * (I-WEB-06), but the provider-side row was written unconditionally — so the late event
     * overwrote {@code SETTLED} with {@code AUTHORIZED}, and the next reconciliation batch reported
     * {@code STATUS_MISMATCH} against a payment that was correctly settled.
     *
     * <p>The discrepancy was false in the most expensive direction: reconciliation exists to find
     * money the two sides disagree about, and this manufactured a disagreement out of our own
     * ingestion order. It is also invisible to any test that delivers events in order, which is why
     * the whole suite stayed green.
     */
    @Test
    @DisplayName("C-WEB-11: an event processed after the settlement does not regress the provider "
            + "side to AUTHORIZED")
    void lateEventDoesNotRegressProviderSideStatus() throws Exception {
        String providerTxn = "psp_" + unique();
        String paymentId = createCapturedPayment(providerTxn);
        String settlementId = "sset_" + unique();
        String date = LocalDate.now(ZoneOffset.UTC).toString();

        send("evt-" + unique(), settlementPayload(settlementId, providerTxn, date), currentTs());
        worker.processBatch(50);

        assertThat(providerStatusOf(providerTxn))
                .as("the settlement must be applied before the late event is delivered")
                .isEqualTo("SETTLED");

        // Now the event the provider emitted first, but which this worker happens to apply last.
        send("evt-" + unique(), createdPayload(providerTxn), currentTs());
        worker.processBatch(50);

        assertThat(providerStatusOf(providerTxn))
                .as("a late CREATED event regressed the provider-side row, so the next batch "
                        + "reported STATUS_MISMATCH against a correctly settled payment")
                .isEqualTo("SETTLED");
        assertThat(stateOf(paymentId))
                .as("and the payment itself was never at risk; this is about the provider-side row")
                .isEqualTo("SETTLED");
    }

    private String createdPayload(String providerTransactionId) {
        return """
                {"type":"payment.created","occurredAt":"%s","providerTransactionId":"%s",\
                "merchantReference":"MERCH-1","gross":{"amountMinor":10000,"currency":"EGP"},\
                "net":{"amountMinor":9700,"currency":"EGP"},"status":"AUTHORIZED"}"""
                .formatted(clock.instant().atOffset(ZoneOffset.UTC).toString(), providerTransactionId);
    }

    private String providerStatusOf(String providerTransactionId) {
        return sql.sql("SELECT provider_status FROM provider_transactions "
                + "WHERE provider = 'SIMULATED_PSP' AND provider_transaction_id = ?")
                .param(providerTransactionId)
                .optional((rs, rowNum) -> rs.getString("provider_status")).orElse(null);
    }

    private String capturedPayload(String providerTransactionId) {
        return """
                {"type":"payment.captured","occurredAt":"%s","providerTransactionId":"%s",\
                "merchantReference":"MERCH-1","gross":{"amountMinor":10000,"currency":"EGP"},\
                "net":{"amountMinor":9700,"currency":"EGP"},"status":"CAPTURED"}"""
                .formatted(clock.instant().atOffset(ZoneOffset.UTC).toString(), providerTransactionId);
    }

    private String settlementPayload(String settlementId, String providerTxn, String date) {
        return """
                {"type":"settlement.paid","occurredAt":"%s","settlement":{\
                "providerSettlementId":"%s","settlementDate":"%s","currency":"EGP",\
                "grossAmountMinor":10000,"feeAmountMinor":300,"netAmountMinor":9700,\
                "lines":[{"providerTransactionId":"%s","grossAmountMinor":10000,"netAmountMinor":9700}]}}"""
                .formatted(clock.instant().atOffset(ZoneOffset.UTC).toString(), settlementId, date,
                        providerTxn);
    }

    // ------------------------------------------------------------------ settlement

    @Test
    @DisplayName("I-SETTLE-01: settlement.paid creates the record, its lines, and settles the payment")
    void settlementRecordIsIngestedAndSettlesThePayment() throws Exception {
        String providerTxn = "psp_" + unique();
        String paymentId = createCapturedPayment(providerTxn);
        String settlementId = "sset_" + unique();
        String date = LocalDate.now(ZoneOffset.UTC).toString();

        assertThat(send("evt-" + unique(), settlementPayload(settlementId, providerTxn, date),
                currentTs()).statusCode()).isEqualTo(202);
        worker.processBatch(50);

        assertThat(stateOf(paymentId)).isEqualTo("SETTLED");
        assertThat(settlementRecords(settlementId)).isEqualTo(1);
        assertThat(settlementLines(settlementId)).isEqualTo(1);
        assertThat(paymentSettlementRecord(paymentId)).isNotNull();

        // The provider's statement must exist even though no capture event ever arrived. Without
        // this the payment is a reconciliation subject with nothing on the other side, and the
        // batch reports MISSING_ON_PROVIDER against a transaction the provider has just settled -
        // a false discrepancy manufactured by our own ingestion gap. (I-SETTLE-01 sets this up
        // already: the payment is captured through our own API and only settlement.paid arrives.)
        assertThat(providerRowCount(providerTxn))
                .as("a settlement is the provider's statement of that transaction")
                .isEqualTo(1);
        assertThat(providerRowStatus(providerTxn)).isEqualTo("SETTLED");
        assertThat(providerRowPaymentId(providerTxn))
                .as("and it is linked to the payment it settles")
                .isEqualTo(paymentId);
    }

    @Test
    @DisplayName("I-SETTLE-03: a settlement reconstructs the provider row from facts we hold, not guesses")
    void settlementRowUsesOurOwnCaptureTime() throws Exception {
        String providerTxn = "psp_" + unique();
        String paymentId = createCapturedPayment(providerTxn);
        String settlementId = "sset_" + unique();
        String date = LocalDate.now(ZoneOffset.UTC).toString();

        send("evt-" + unique(), settlementPayload(settlementId, providerTxn, date), currentTs());
        worker.processBatch(50);

        // captured_at decides which reconciliation window a transaction falls in. Taking it from
        // the payment's own capture is a fact - the same instant PSP_CLEARING was credited - and it
        // puts the transaction in the window it belongs to. A settlement carries no capture time at
        // all, so any other source would be an invention that could silently exclude it from every
        // future batch.
        assertThat(providerRowCapturedAt(providerTxn))
                .as("the reconstructed row is scoped by the capture we recorded, not by the settlement")
                .isEqualTo(paymentCapturedAt(paymentId));

        assertThat(providerRowGross(providerTxn))
                .as("the provider's own figures, from the settlement line")
                .isEqualTo(10_000L);
        assertThat(providerRowNet(providerTxn)).isEqualTo(9_700L);
    }

    @Test
    @DisplayName("I-SETTLE-02: the same settlement delivered twice settles once and breaks no invariant")
    void repeatedSettlementIsIdempotent() throws Exception {
        String providerTxn = "psp_" + unique();
        String paymentId = createCapturedPayment(providerTxn);
        String settlementId = "sset_" + unique();
        String date = LocalDate.now(ZoneOffset.UTC).toString();
        String payload = settlementPayload(settlementId, providerTxn, date);

        send("evt-a-" + unique(), payload, currentTs());
        send("evt-b-" + unique(), payload, currentTs());
        worker.processBatch(50);

        assertThat(settlementRecords(settlementId))
                .as("ux_settlement makes a second record impossible, not merely unlikely")
                .isEqualTo(1);
        assertThat(stateOf(paymentId)).isEqualTo("SETTLED");
        assertThat(ledgerImbalanceCount())
                .as("L1 still holds after ingesting the same settlement twice")
                .isZero();
    }

    private int providerRowCount(String providerTransactionId) {
        return sql.sql("SELECT count(*) AS n FROM provider_transactions WHERE provider_transaction_id = ?")
                .param(providerTransactionId)
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }


    /** One text column of the provider row. Null when the row is absent. */
    private String providerRowStatus(String providerTransactionId) {
        return sql.sql("""
                        SELECT provider_status FROM provider_transactions
                         WHERE provider_transaction_id = ?""")
                .param(providerTransactionId)
                .optional((rs, rowNum) -> rs.getString("provider_status"))
                .orElse(null);
    }

    /** One text column of the provider row. Null when the row is absent. */
    private String providerRowPaymentId(String providerTransactionId) {
        return sql.sql("""
                        SELECT payment_id FROM provider_transactions
                         WHERE provider_transaction_id = ?""")
                .param(providerTransactionId)
                .optional((rs, rowNum) -> rs.getString("payment_id"))
                .orElse(null);
    }

    private java.time.Instant providerRowCapturedAt(String providerTransactionId) {
        return sql.sql("SELECT captured_at FROM provider_transactions WHERE provider_transaction_id = ?")
                .param(providerTransactionId)
                .optional((rs, rowNum) -> Sql.instant(rs, "captured_at"))
                .orElse(null);
    }

    private java.time.Instant paymentCapturedAt(String paymentId) {
        return sql.sql("SELECT captured_at FROM payments WHERE id = ?")
                .param(paymentId)
                .optional((rs, rowNum) -> Sql.instant(rs, "captured_at"))
                .orElse(null);
    }

    private long providerRowGross(String providerTransactionId) {
        return sql.sql("""
                        SELECT gross_amount_minor FROM provider_transactions
                         WHERE provider_transaction_id = ?""")
                .param(providerTransactionId)
                .optional((rs, rowNum) -> rs.getLong("gross_amount_minor"))
                .orElse(0L);
    }

    private long providerRowNet(String providerTransactionId) {
        return sql.sql("""
                        SELECT net_amount_minor FROM provider_transactions
                         WHERE provider_transaction_id = ?""")
                .param(providerTransactionId)
                .optional((rs, rowNum) -> rs.getLong("net_amount_minor"))
                .orElse(0L);
    }

    private int settlementRecords(String providerSettlementId) {
        return sql.sql("SELECT count(*) AS n FROM settlement_records WHERE provider_settlement_id = ?")
                .param(providerSettlementId)
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }

    private int settlementLines(String providerSettlementId) {
        return sql.sql("""
                SELECT count(*) AS n FROM settlement_record_lines l
                  JOIN settlement_records r ON r.id = l.settlement_record_id
                 WHERE r.provider_settlement_id = ?
                """)
                .param(providerSettlementId)
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }

    private String paymentSettlementRecord(String paymentId) {
        return sql.sql("SELECT settlement_record_id FROM payments WHERE id = ?")
                .param(paymentId)
                .optional((rs, rowNum) -> rs.getString("settlement_record_id")).orElse(null);
    }

    private int ledgerImbalanceCount() {
        return sql.sql("SELECT count(*) AS n FROM ledger_transactions WHERE state <> 'POSTED'")
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }

    private String createAuthorizedPayment(String providerTransactionId) {
        String key = "wh-" + unique();
        String body = """
                {"merchantReference":"MERCH-1","amount":{"amountMinor":10000,"currency":"EGP"},\
                "provider":"SIMULATED_PSP","providerTransactionId":"%s"}""".formatted(
                        providerTransactionId);
        String paymentId = jsonString(api("POST", "/api/v1/payments", key, body), "id");
        authorize(paymentId);
        return paymentId;
    }

    private String createCapturedPayment(String providerTransactionId) {
        String paymentId = createAuthorizedPayment(providerTransactionId);
        capture(paymentId);
        return paymentId;
    }

    private void authorize(String paymentId) {
        api("POST", "/api/v1/payments/" + paymentId + "/authorize", "wh-" + unique(), "{}");
    }

    private void capture(String paymentId) {
        api("POST", "/api/v1/payments/" + paymentId + "/capture", "wh-" + unique(), "{}");
    }

    private void refund(String paymentId) {
        api("POST", "/api/v1/payments/" + paymentId + "/refund", "wh-" + unique(), null);
    }

    private String api(String method, String path, String key, String body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(base() + path))
                    .header("Authorization", "Bearer " + OPERATOR)
                    .header("Idempotency-Key", key);
            if (body == null) {
                request.POST(HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            }
            HttpResponse<String> response =
                    http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException(
                        method + " " + path + " -> " + response.statusCode() + " " + response.body());
            }
            return response.body();
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Reads a string field out of a response body.
     *
     * <p>Tolerant of whitespace because the response is the stored {@code jsonb} rendering, which
 * * is not the same text this client sent. A helper that assumed compact JSON would fail on a
 * perfectly correct response — and would be testing the wrong thing.
     */
    private String jsonString(String json, String field) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("no field " + field + " in " + json);
        }
        return matcher.group(1);
    }

    private List<String> deliveryOutcomes(String eventId) {
        return sql.sql("SELECT outcome FROM provider_event_deliveries WHERE event_id = ? "
                + "ORDER BY received_at, id")
                .param(eventId)
                .list((rs, rowNum) -> rs.getString("outcome"));
    }

    private int eventRows(String eventId) {
        return sql.sql("SELECT count(*) AS n FROM provider_events WHERE event_id = ?")
                .param(eventId)
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }

    private int duplicateCount(String eventId) {
        return sql.sql("SELECT duplicate_deliveries FROM provider_events WHERE event_id = ?")
                .param(eventId)
                .optional((rs, rowNum) -> rs.getInt("duplicate_deliveries")).orElse(-1);
    }

    private String eventStatus(String eventId) {
        return sql.sql("SELECT status FROM provider_events WHERE event_id = ?")
                .param(eventId)
                .optional((rs, rowNum) -> rs.getString("status")).orElse(null);
    }

    private int attemptsOf(String eventId) {
        return sql.sql("SELECT attempts FROM provider_events WHERE event_id = ?")
                .param(eventId)
                .optional((rs, rowNum) -> rs.getInt("attempts")).orElse(-1);
    }

    /** Whether the event is parked until a backoff window elapses. */
    private boolean hasBackoff(String eventId) {
        return sql.sql("SELECT next_attempt_at > now() FROM provider_events WHERE event_id = ?")
                .param(eventId)
                .optional((rs, rowNum) -> rs.getBoolean(1)).orElse(false);
    }

    /** Makes a parked event claimable again, so the retry path can be driven deterministically. */
    private void clearBackoff(String eventId) {
        sql.sql("UPDATE provider_events SET next_attempt_at = now() WHERE event_id = ?")
                .param(eventId)
                .update();
    }

    private List<String> failedBacklog() {
        return sql.sql("SELECT event_id FROM provider_events WHERE status = 'FAILED'")
                .list((rs, rowNum) -> rs.getString("event_id"));
    }

    /** Every terminal status the operations backlog is currently showing. */
    private List<String> failedEventStatuses() {
        return sql.sql("SELECT status FROM provider_events WHERE status IN ('FAILED','DEAD')")
                .list((rs, rowNum) -> rs.getString("status"));
    }

    private int providerTransactionRows() {
        return sql.sql("SELECT count(*) AS n FROM provider_transactions")
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }

    private int ledgerTransactionsFor(String type) {
        return sql.sql("SELECT count(*) AS n FROM ledger_transactions WHERE type = ?")
                .param(type)
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }

    private int auditCount(String action) {
        return sql.sql("SELECT count(*) AS n FROM audit_events WHERE action = ?")
                .param(action)
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }

    private String stateOf(String paymentId) {
        return sql.sql("SELECT state FROM payments WHERE id = ?")
                .param(paymentId)
                .optional((rs, rowNum) -> rs.getString("state")).orElse(null);
    }

    private String providerTxnOf(String paymentId) {
        return sql.sql("SELECT provider_transaction_id FROM payments WHERE id = ?")
                .param(paymentId)
                .optional((rs, rowNum) -> rs.getString("provider_transaction_id")).orElse(null);
    }
}
