package com.reconcile.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.service.IdempotencyService;
import com.reconcile.support.SharedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Idempotency as a client experiences it: over a socket, with the real filter chain.
 *
 * <p>Maps to rows {@code I-IDEM-01}, {@code I-IDEM-02}, {@code I-IDEM-03} and {@code I-IDEM-04}.
 *
 * <p>The dangerous property of this system is not that a retry fails — it is that a retry
 * <i>succeeds</i> and moves money twice. Nothing about that is visible from the service layer: a
 * double charge looks exactly like a correct charge unless you count the rows and compare the two
 * response bodies byte for byte, which is what these tests do.
 *
 * <p>No truncation between tests. Each test owns a unique {@code merchantReference}, so the counts
 * are exact without a shared-table reset that could hide an ordering problem. The container is
 * started by hand for the reason documented in {@link ApiHttpIT}: the Testcontainers extension does
 * not initialise under {@code RANDOM_PORT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyHttpIT {

    private static final String OPERATOR = "operator-secret-token";


    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    Sql sql;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedPostgres::jdbcUrl);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("reconcile.security.operator-token", () -> OPERATOR);
        registry.add("reconcile.security.admin-token", () -> "admin-secret-token");
    }

    // ------------------------------------------------------------------ I-IDEM-01

    @Test
    @DisplayName("I-IDEM-01: the same key and body twice makes one payment and replays the response")
    void identicalRetryIsReplayedNotRedone() throws Exception {
        String key = "idem-01-" + java.util.UUID.randomUUID();
        String body = create("MERCH-IDEM-01");

        HttpResponse<String> first = postCreate(key, body);
        HttpResponse<String> second = postCreate(key, body);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(first.headers().firstValue("Idempotency-Replayed")).contains("false");

        assertThat(second.statusCode())
                .as("a replay repeats the original status, not a new one")
                .isEqualTo(201);
        assertThat(second.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(second.body())
                .as("byte-identical: the stored bytes are what the first caller received")
                .isEqualTo(first.body());
        assertThat(second.headers().firstValue("Location"))
                .as("the same Location, which is what resource_id in the record is for")
                .isEqualTo(first.headers().firstValue("Location"));

        assertThat(paymentsFor("MERCH-IDEM-01"))
                .as("one payment, however many times the client retried")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ I-IDEM-02

    @Test
    @DisplayName("I-IDEM-02: the same key with a different body is a conflict and creates nothing")
    void reusedKeyWithDifferentBodyConflicts() throws Exception {
        String key = "idem-02-" + java.util.UUID.randomUUID();

        HttpResponse<String> first = postCreate(key, create("MERCH-IDEM-02-A"));
        HttpResponse<String> conflict = postCreate(key, create("MERCH-IDEM-02-B"));

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.body()).contains("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(paymentsFor("MERCH-IDEM-02-B"))
                .as("a refused request has no side effect")
                .isEqualTo(0);

        assertThat(postCreate(key, create("MERCH-IDEM-02-A")).body())
                .as("the original record is untouched, so its owner still gets its response")
                .isEqualTo(first.body());
    }

    // ------------------------------------------------------------------ I-IDEM-03

    @Test
    @Timeout(120)
    @DisplayName("I-IDEM-03: 32 concurrent retries of one key produce one payment and 32 identical bodies")
    void concurrentRetriesCollapseToOnePayment() throws Exception {
        int threads = 32;
        String key = "idem-03-" + java.util.UUID.randomUUID();
        String body = create("MERCH-IDEM-03");

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<HttpResponse<String>>> responses = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<HttpResponse<String>> call = () -> {
                    ready.countDown();
                    go.await(30, TimeUnit.SECONDS);
                    return postCreate(key, body);
                };
                responses.add(pool.submit(call));
            }

            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<HttpResponse<String>> results = new ArrayList<>();
            for (Future<HttpResponse<String>> response : responses) {
                results.add(response.get(90, TimeUnit.SECONDS));
            }

            assertThat(results).allSatisfy(response -> assertThat(response.statusCode())
                    .as("no loser may be answered 5xx or 409: the insert blocks until the winner "
                            + "commits, so the loser always reads a COMPLETED record")
                    .isEqualTo(201));
            assertThat(results.stream().map(HttpResponse::body).distinct())
                    .as("every caller is told the same thing, because there is one answer")
                    .hasSize(1);
            assertThat(paymentsFor("MERCH-IDEM-03")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ I-IDEM-04

    @Test
    @DisplayName("I-IDEM-04: a refused capture replays identically and changes nothing")
    void businessRefusalIsStoredAndReplayed() throws Exception {
        String reference = "MERCH-IDEM-04";
        HttpResponse<String> created = postCreate(
                "idem-04-create-" + java.util.UUID.randomUUID(), create(reference));
        String paymentId = sql.sql("SELECT id FROM payments WHERE merchant_reference = ?")
                .param(reference)
                .optional((rs, rowNum) -> rs.getString("id"))
                .orElseThrow();
        assertThat(created.statusCode()).isEqualTo(201);

        String key = "idem-04-capture-" + java.util.UUID.randomUUID();
        HttpResponse<String> refused = postCapture(key, paymentId, "{}");
        HttpResponse<String> replayed = postCapture(key, paymentId, "{}");

        assertThat(refused.statusCode())
                .as("capture is illegal from CREATED, and that is a business answer")
                .isEqualTo(409);
        assertThat(refused.body()).contains("PAYMENT_INVALID_STATE");
        assertThat(replayed.statusCode()).isEqualTo(409);
        assertThat(replayed.body())
                .as("stored and replayed verbatim")
                .isEqualTo(refused.body());

        assertThat(stateOf(paymentId))
                .as("no state change on either attempt")
                .isEqualTo("CREATED");
        assertThat(captureTransactionsFor(paymentId))
                .as("and no ledger posting: a refusal that posted money would be a bug, not a refusal")
                .isEqualTo(0);

        HttpResponse<String> conflict = postCapture(key, paymentId,
                "{\"expectedAmount\":{\"amountMinor\":1,\"currency\":\"EGP\"}}");
        assertThat(conflict.statusCode())
                .as("the stored refusal is a real record, so a different body still conflicts")
                .isEqualTo(409);
        assertThat(conflict.body()).contains("IDEMPOTENCY_KEY_CONFLICT");
    }

    // ------------------------------------------------------------------ slow path

    @Test
    @DisplayName("I-IDEM-03 (slow path): a key still IN_PROGRESS is 409 with Retry-After, not a wait")
    void inProgressKeyIsRefusedWithRetryAfter() throws Exception {
        String key = "idem-03b-" + java.util.UUID.randomUUID();
        String body = create("MERCH-IDEM-03B");

        // The residual case from spec 03 section A5: a record left IN_PROGRESS by a transaction that
        // is still open. It cannot normally survive a crash - the reservation shares the command's
        // transaction - but the branch exists for the case where the winner's transaction spans more
        // than one hop, and the contract for it is a refusal with a Retry-After, never an open wait.
        // Seeding the row directly is what makes that branch testable without a racing thread.
        byte[] raw = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        sql.sql("""
                INSERT INTO idempotency_records
                    (id, endpoint, idem_key, fingerprint, status, request_id, expires_at)
                VALUES ('idem_in_progress_probe', ?, ?, ?, 'IN_PROGRESS', 'req_probe',
                        now() + interval '24 hours')
                """)
                .params(
                        IdempotencyService.CREATE_PAYMENT.key(),
                        key,
                        IdempotencyService.fingerprint(IdempotencyService.CREATE_PAYMENT, raw))
                .update();

        HttpResponse<String> response = postCreate(key, body);

        assertThat(response.statusCode())
                .as("holding a request open on a lock turns a client timeout into a thread that "
                        + "never finishes")
                .isEqualTo(409);
        assertThat(response.body()).contains("REQUEST_IN_PROGRESS");
        assertThat(response.headers().firstValue("Retry-After"))
                .as("so the client knows when to come back instead of guessing a backoff")
                .contains("1");
        assertThat(paymentsFor("MERCH-IDEM-03B")).isZero();
    }

    // ------------------------------------------------------------------ C-IDEM-06

    /**
     * The replay guarantee is bounded, and the bound is part of the contract.
     *
     * <p>Asserted because it is the one behaviour here that is a <i>cost</i> rather than a
     * protection: after the retention window a reused key stops being a replay and becomes a fresh
     * request, so a client retrying far too late creates a second payment instead of receiving the
     * first answer. That is the correct trade — the alternative is keeping idempotency records
     * forever — but it is only correct if it is true deliberately, which is what this pins.
     */
    @Test
    @DisplayName("C-IDEM-06: a key replayed after the retention window is treated as new, not replayed")
    void replayAfterTheRetentionWindowIsANewRequest() throws Exception {
        String key = "idem-retention-" + java.util.UUID.randomUUID();
        String reference = "MERCH-IDEM-RETENTION";
        String body = create(reference);

        HttpResponse<String> first = postCreate(key, body);
        assertThat(first.statusCode()).isEqualTo(201);

        // Expired exactly as the retention sweeper will leave it.
        sql.sql("UPDATE idempotency_records SET expires_at = now() - interval '1 hour' "
                + "WHERE idem_key = ?")
                .param(key).update();

        HttpResponse<String> afterExpiry = postCreate(key, body);

        assertThat(afterExpiry.statusCode())
                .as("an expired record is not replayed: past the window the honest answer is a new "
                        + "request, not a stale copy of an old one")
                .isEqualTo(201);
        assertThat(afterExpiry.headers().firstValue("Idempotency-Replayed"))
                .as("and it must say so, rather than 201 and a replayed header together")
                .contains("false");
        assertThat(afterExpiry.headers().firstValue("Location"))
                .as("a different payment, so the response is not a copy of the stored one")
                .isNotEqualTo(first.headers().firstValue("Location"));
        assertThat(paymentsFor(reference))
                .as("two payments. This is the documented cost of replaying outside the window, "
                        + "and it is why the window is stated rather than left implicit.")
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------ helpers

    private static String create(String merchantReference) {
        return "{\"merchantReference\":\"" + merchantReference
                + "\",\"amount\":{\"amountMinor\":10000,\"currency\":\"EGP\"}}";
    }

    private HttpResponse<String> postCreate(String key, String body) throws Exception {
        return send(HttpRequest.newBuilder()
                .uri(URI.create(base() + "/api/v1/payments"))
                .header("Authorization", "Bearer " + OPERATOR)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> postCapture(String key, String paymentId, String body)
            throws Exception {
        return send(HttpRequest.newBuilder()
                .uri(URI.create(base() + "/api/v1/payments/" + paymentId + "/capture"))
                .header("Authorization", "Bearer " + OPERATOR)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int paymentsFor(String merchantReference) {
        return sql.sql("SELECT count(*) AS total FROM payments WHERE merchant_reference = ?")
                .param(merchantReference)
                .optional((rs, rowNum) -> rs.getInt("total"))
                .orElse(0);
    }

    private String stateOf(String paymentId) {
        return sql.sql("SELECT state FROM payments WHERE id = ?")
                .param(paymentId)
                .optional((rs, rowNum) -> rs.getString("state"))
                .orElse(null);
    }

    private int captureTransactionsFor(String paymentId) {
        return sql.sql("""
                SELECT count(*) AS total
                  FROM payment_ledger_transactions
                 WHERE payment_id = ? AND role = 'CAPTURE'
                """)
                .param(paymentId)
                .optional((rs, rowNum) -> rs.getInt("total"))
                .orElse(0);
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }
}
