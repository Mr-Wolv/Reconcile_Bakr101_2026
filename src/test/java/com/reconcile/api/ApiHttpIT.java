package com.reconcile.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.support.SharedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Proves that a request carrying a valid bearer token actually reaches the API.
 *
 * <p>Maps to rows {@code I-SEC-03} and {@code I-SEC-04}.
 *
 * <p>This class exists because of a defect that shipped with a fully green suite: every
 * authenticated request was answered {@code 401} by the running image, while
 * {@code SecurityConfigIT} proved the token bound and resolved to the right role. Nothing in the
 * repository issued an HTTP request, so nothing noticed. This is that missing test.
 *
 * <p><b>Why the container is managed by hand.</b> The Testcontainers JUnit extension fails to
 * initialise the container when the test uses {@code RANDOM_PORT} or {@code @AutoConfigureMockMvc},
 * raising "Container POSTGRES needs to be initialized" before any test runs. Starting the container
 * in {@code @BeforeAll} avoids the extension entirely, and {@code @DynamicPropertySource} is
 * evaluated lazily - after {@code @BeforeAll}, and before the context is built - so the JDBC URL is
 * available in time.
 *
 * <p>Over a real socket rather than a mock, because the bug lived in the deployed filter chain,
 * which is exactly what a simulated dispatcher would not have exercised.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiHttpIT {

    private static final String OPERATOR = "operator-secret-token";
    private static final String ADMIN = "admin-secret-token";


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
        registry.add("reconcile.security.admin-token", () -> ADMIN);
        registry.add("reconcile.security.default-tokens",
                () -> "dev-operator-token,dev-admin-token");
        registry.add("reconcile.provider.webhook-secret", () -> "webhook-secret-token");
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

    @Test
    @DisplayName("I-SEC-03: a valid operator token is accepted; a missing or wrong one is refused")
    void bearerTokenIsAccepted() throws Exception {
        assertThat(get("/api/v1/accounts", OPERATOR).statusCode())
                .as("a valid operator token must reach the API")
                .isEqualTo(200);

        assertThat(get("/api/v1/accounts", null).statusCode())
                .as("no token")
                .isEqualTo(401);
        assertThat(get("/api/v1/accounts", "wrong-token").statusCode())
                .as("an unknown token")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("I-SEC-03: the admin token also reads, because ADMIN implies OPERATOR")
    void adminTokenImpliesOperatorOverHttp() throws Exception {
        assertThat(get("/api/v1/accounts", ADMIN).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("I-SEC-04: an operator is forbidden on an admin-only route, an admin is not")
    void rolesAreEnforcedOverHttp() throws Exception {
        assertThat(post("/api/v1/payments/pay_missing/fail", OPERATOR, "{\"failureCode\":\"DECLINED\"}")
                .statusCode())
                .as("an operator must not be able to fail a payment")
                .isEqualTo(403);

        assertThat(post("/api/v1/payments/pay_missing/fail", ADMIN, "{\"failureCode\":\"DECLINED\"}")
                .statusCode())
                .as("authorised, so it reaches the service and reports the payment does not exist")
                .isEqualTo(404);
    }

    /**
     * E2E-SEC-01: every admin-only route, walked.
     *
     * <p>The single-route check above proves the mechanism on one endpoint. It does not prove the
     * route table, and the route table is the thing most likely to be wrong: an endpoint added
     * later without a matching {@code requestMatchers} line is an authorisation hole that no unit
     * test notices, because {@link com.reconcile.security.SecurityConfigIT} tests the configuration
     * object rather than the deployed chain.
     *
     * <p>Each route is sent as an operator and must be refused <i>before</i> the body is examined.
     * A {@code 404} or {@code 400} would mean the request reached the controller, which is the
     * failure this asserts against — so the status alone is checked and no response body is
     * required to be anything in particular.
     */
    @Test
    @DisplayName("E2E-SEC-01: every admin-only route is refused for an operator token")
    void everyAdminOnlyRouteIsRefusedForAnOperator() throws Exception {
        // Bodies are deliberately valid against each endpoint's own request record. An invalid body
        // would be refused by validation anyway, and a test that passed because of a validation
        // failure rather than because of authorisation would be proving nothing.
        String[][] adminOnlyRoutes = {
            {"/api/v1/payments/pay_missing/fail", "{\"failureCode\":\"DECLINED\"}"},
            {"/api/v1/payouts", "{\"merchantReference\":\"M\",\"paymentIds\":[\"pay_missing\"]}"},
            {"/api/v1/ledger/transactions/txn_missing/reverse", "{\"reason\":\"unauthorised\"}"},
            {"/api/v1/reconciliation/batches",
                    "{\"windowStart\":\"2026-01-01T00:00:00Z\",\"windowEnd\":\"2026-01-02T00:00:00Z\"}"},
            {"/api/v1/reconciliation/cases/case_missing/resolve",
                    "{\"resolutionNote\":\"unauthorised operator attempted a resolve\"}"},
            {"/api/v1/reconciliation/cases/case_missing/write-off",
                    "{\"resolutionNote\":\"unauthorised operator attempted a write off\","
                            + "\"resolutionAction\":\"PROVIDER_ERROR_CONFIRMED\"}"},
            {"/api/v1/provider/sync",
                    "{\"windowStart\":\"2026-01-01T00:00:00Z\",\"windowEnd\":\"2026-01-02T00:00:00Z\"}"},
        };

        for (String[] route : adminOnlyRoutes) {
            assertThat(post(route[0], OPERATOR, route[1]).statusCode())
                    .as("operator must be refused on admin-only route %s; anything other than 403 "
                            + "here means the request passed the filter chain", route[0])
                    .isEqualTo(403);
        }
    }

    @Test
    @DisplayName("An unknown path is a 404 problem document, not a 500")
    void unknownPathIsNotFound() throws Exception {
        HttpResponse<String> response = get("/api/v1/no-such-thing", OPERATOR);

        assertThat(response.statusCode())
                .as("a mistyped URL must not be reported as a server fault")
                .isEqualTo(404);
        assertThat(response.body())
                .as("and it must be a problem document, not an HTML error page")
                .contains("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("POST /payments without an Idempotency-Key is refused")
    void createPaymentRequiresAnIdempotencyKey() throws Exception {
        HttpResponse<String> response = post("/api/v1/payments", OPERATOR,
                "{\"merchantReference\":\"M\",\"amount\":{\"amountMinor\":10000,\"currency\":\"EGP\"}}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    @DisplayName("E2E-API-03: an unknown field on a payment request is 400, never silently ignored")
    void unknownFieldOnPaymentRequestIsRefused() throws Exception {
        // A misspelled "amountMinor" at the top level is the dangerous case, not a hypothetical
        // one: the request still binds, the payment is still created, and the caller believes the
        // field was honoured. Silence here is how a merchant gets under-charged and never told.
        HttpResponse<String> response = postWithKey("/api/v1/payments", OPERATOR,
                "{\"merchantReference\":\"M-UNKNOWN-FIELD\","
                        + "\"amount\":{\"amountMinor\":10000,\"currency\":\"EGP\"},"
                        + "\"amountMinor\":4242}",
                "idem-unknown-field-" + java.util.UUID.randomUUID());

        assertThat(response.statusCode())
                .as("body was: %s", response.body())
                .isEqualTo(400);
        assertThat(response.body())
                .as("and it must be the table's code for a body that does not bind — an unknown "
                        + "field is a binding failure, so 400 MALFORMED_REQUEST per the error "
                        + "table, not a server fault and not a business rejection")
                .contains("MALFORMED_REQUEST");
        assertThat(paymentsFor("M-UNKNOWN-FIELD"))
                .as("a refused body must leave nothing behind; a created payment here would mean "
                        + "the field was dropped rather than rejected")
                .isZero();
    }

    @Test
    @DisplayName("The webhook route is not token-protected: the signature is the credential")
    void webhookRouteIsNotTokenProtected() throws Exception {
        assertThat(post("/api/v1/provider/webhooks", null, "{}").statusCode())
                .as("401 here would mean a bearer token was demanded instead of a signature")
                .isNotEqualTo(401);
    }

    @Test
    @DisplayName("Every response carries the correlation id")
    void responsesCarryTheRequestId() throws Exception {
        assertThat(get("/api/v1/accounts", OPERATOR).headers().firstValue("X-Request-Id"))
                .as("so a client can quote it and an operator can find the request")
                .isPresent();
    }

    /**
     * The echoed id is sanitised before it is echoed.
     *
     * <p>A client-supplied correlation id lands in log lines and in {@code audit_events}. A value
     * carrying a newline would therefore let a caller write a forged log entry, and one of
     * unbounded length would land in an indexed column. Neither is visible in any other test
     * because {@link #responsesCarryTheRequestId()} only checks that the header is present.
     *
     * <p><b>Over a raw socket, not the JDK client.</b> {@link HttpClient} rejects a header value
     * containing a newline before it leaves the process, so a test built on it would pass without
     * the request ever reaching the server and would prove nothing about the filter. A raw socket
     * sends the bytes and lets the server decide, which is the situation the sanitiser exists for.
     */
    @Test
    @DisplayName("A client-supplied correlation id is sanitised before it is echoed")
    void clientSuppliedRequestIdIsSanitised() throws Exception {
        String response = rawRequest(
                "GET /api/v1/accounts HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\n"
                        + "Authorization: Bearer " + OPERATOR + "\r\n"
                        + "X-Request-Id: req_forged log line with spaces\r\n"
                        + "Connection: close\r\n\r\n");

        String echoed = headerValue(response, "X-Request-Id");

        assertThat(echoed)
                .as("spaces are outside the safe set and must be dropped, not stored "
                        + "(raw response was: %s)", response)
                .isEqualTo("req_forgedloglinewithspaces");
    }

    /**
     * A newline in a correlation id never reaches the filter, because the container refuses it.
     *
     * <p>Recorded because {@link RequestIdFilter} claims the sanitiser is what stops log forging,
     * and that claim is only half true: the defence is the container's header parser, which
     * rejects the request before any application code runs. The filter's own character set is
     * still worth keeping - it is what handles the characters a well-formed header <i>can</i>
     * carry - but this test pins the actual layer of the defence so the comment is not read as
     * the sole one.
     */
    @Test
    @DisplayName("A correlation id carrying a newline is refused by the container, not stored")
    void newlineInRequestIdNeverReachesTheApplication() throws Exception {
        String response = rawRequest(
                "GET /api/v1/accounts HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\n"
                        + "Authorization: Bearer " + OPERATOR + "\r\n"
                        + "X-Request-Id: req_forged\nINFO forged audit row\r\n"
                        + "Connection: close\r\n\r\n");

        assertThat(response)
                .as("the request must never be served with a newline in its correlation id; "
                        + "a 2xx here would mean it reached the filter intact (raw response: %s)",
                        response)
                .doesNotContain(" 200 ");
        assertThat(headerValue(response, "X-Request-Id"))
                .as("and no echoed id is built from the forged value")
                .doesNotContain("forged");
    }

    /**
     * An unbounded correlation id is truncated, not stored whole.
     *
     * <p>The value reaches an indexed column and a log line. Left unbounded it is both an index
     * bloat vector and a way to push other fields out of view in a log viewer.
     */
    @Test
    @DisplayName("A long correlation id is truncated to the documented limit")
    void longRequestIdIsTruncated() throws Exception {
        String tooLong = "req_" + "a".repeat(500);

        String echoed = http.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(base() + "/api/v1/accounts"))
                                .header("Authorization", "Bearer " + OPERATOR)
                                .header("X-Request-Id", tooLong)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .headers().firstValue("X-Request-Id").orElse("");

        assertThat(echoed.length())
                .as("an unbounded correlation id would land in an indexed column unbounded")
                .isLessThanOrEqualTo(64);
        assertThat(echoed)
                .as("truncated, not rejected: refusing a request over its correlation id would be "
                        + "a worse answer than shortening it")
                .startsWith("req_aaaa");
    }

    /**
     * An id made entirely of unsafe characters falls back to a generated one.
     *
     * <p>The sanitiser can legitimately produce an empty string, and an empty correlation id is
     * worse than a generated one: every row written during the request would share it.
     */
    @Test
    @DisplayName("A correlation id with nothing safe in it is replaced, not left blank")
    void unusableRequestIdFallsBackToAGeneratedOne() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/api/v1/accounts"))
                .header("Authorization", "Bearer " + OPERATOR)
                .header("X-Request-Id", "!!! ??? ###")
                .GET()
                .build();

        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString())
                .headers().firstValue("X-Request-Id"))
                .as("a blank correlation id would be attached to every audit row of the request")
                .hasValueSatisfying(id -> assertThat(id).isNotBlank().startsWith("req_"));
    }

    // ------------------------------------------- the five endpoints with no HTTP coverage
    //
    // Each of these had a service test and no HTTP test at all: the handler was never invoked with
    // a real request, so the mapping, the path-variable binding, the body binding and the status
    // code were all unverified. That is the same gap the I-SEC-03 test above was written for after
    // a shipped defect proved it - an endpoint nobody ever calls is an endpoint nobody knows works.
    //
    // Each test therefore checks two things: that the route answers 200/204/404 as documented, and
    // that the call left the state its contract promises. A status code on its own would pass just
    // as well against a handler that wrote the wrong row.

    /** E2E-API-07: the ledger transaction view binds the path variable and returns the entries. */
    @Test
    @DisplayName("E2E-API-07: GET /ledger/transactions/{id} returns the transaction with its entries, "
            + "and 404 for one that does not exist")
    void ledgerTransactionIsReadableOverHttp() throws Exception {
        String transactionId = capturedPaymentTransaction();

        HttpResponse<String> found = get("/api/v1/ledger/transactions/" + transactionId, OPERATOR);

        assertThat(found.statusCode())
                .as("body was: %s", found.body())
                .isEqualTo(200);
        assertThat(found.body())
                .as("the id must be echoed back, or the caller cannot tell what it was told about")
                .contains(transactionId);
        assertThat(found.body())
                .as("and the entries are the point of the endpoint - it is the investigative view, "
                        + "so a header without them would make it useless")
                .contains("PSP_CLEARING")
                .contains("PLATFORM_FEE_REVENUE")
                .contains("MERCHANT_PAYABLE");

        HttpResponse<String> missing = get("/api/v1/ledger/transactions/txn_missing", OPERATOR);
        assertThat(missing.statusCode())
                .as("an unknown id must be a 404 problem document, not an empty 200: a client that "
                        + "receives 200 with no transactions has been told nothing about anything")
                .isEqualTo(404);
        assertThat(missing.body()).contains("RESOURCE_NOT_FOUND");
    }

    /** E2E-API-08: the admin-only reversal, and L4 — the original is never edited. */
    @Test
    @DisplayName("E2E-API-08: POST /ledger/transactions/{id}/reverse posts a new REVERSAL, leaves "
            + "the original untouched, and 404s on an unknown id")
    void reversalIsPostedOverHttpWithoutEditingTheOriginal() throws Exception {
        String transactionId = capturedPaymentTransaction();

        HttpResponse<String> response =
                post("/api/v1/ledger/transactions/" + transactionId + "/reverse", ADMIN,
                        "{\"reason\":\"operator correction after a duplicate capture\"}");

        assertThat(response.statusCode())
                .as("body was: %s", response.body())
                .isEqualTo(200);
        String reversalId = response.body();
        assertThat(reversalId)
                .as("the new transaction's id is the whole answer, so it has to be in the body")
                .isNotBlank();

        assertThat(reversalPointsAt(reversalId))
                .as("L4: a reversal is identified by the transaction it reverses, so the original "
                        + "is left byte-for-byte alone and answered by the existence of this row")
                .isEqualTo(transactionId);
        assertThat(originalStillPosted(transactionId))
                .as("and the original must still be POSTED, not marked reversed")
                .isTrue();
        assertThat(entriesAreMirored(transactionId, reversalId))
                .as("an exact sign flip - same accounts, opposite directions. Anything else would "
                        + "be a correction dressed up as a reversal")
                .isTrue();

        HttpResponse<String> missing =
                post("/api/v1/ledger/transactions/txn_missing/reverse", ADMIN, "{\"reason\":\"nope\"}");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(post("/api/v1/ledger/transactions/txn_missing/reverse", ADMIN, null).statusCode())
                .as("the body is optional and defaults the reason; a 400 here would make the "
                        + "default unreachable")
                .isEqualTo(404);
    }

    /** E2E-API-09: the batch view, by id, with its own summary. */
    @Test
    @DisplayName("E2E-API-09: GET /reconciliation/batches/{id} returns that batch's window and "
            + "summary, and 404s on an unknown id")
    void reconciliationBatchIsReadableOverHttp() throws Exception {
        HttpResponse<String> created = post("/api/v1/reconciliation/batches", ADMIN, """
                {"provider":"SIMULATED_PSP","windowStart":"2026-01-01T00:00:00Z",\
                "windowEnd":"2026-01-02T00:00:00Z","async":false}""");
        assertThat(created.statusCode()).isEqualTo(200);
        String batchId = jsonString(created.body(), "id");

        HttpResponse<String> found = get("/api/v1/reconciliation/batches/" + batchId, OPERATOR);

        assertThat(found.statusCode())
                .as("body was: %s", found.body())
                .isEqualTo(200);
        assertThat(found.body())
                .as("the window it ran over is what makes a batch interpretable later")
                .contains("2026-01-01T00:00:00Z")
                .contains("2026-01-02T00:00:00Z");
        assertThat(found.body())
                .as("and the run must have completed rather than sitting RUNNING forever")
                .contains("\"status\":\"COMPLETED\"");
        assertThat(found.body())
                .as("totals are part of the view - an operator reads the summary, not the row")
                .contains("\"matched\"");

        HttpResponse<String> missing = get("/api/v1/reconciliation/batches/rbat_missing", OPERATOR);
        assertThat(missing.statusCode())
                .as("a mistyped id must not read as an empty batch")
                .isEqualTo(404);
        assertThat(missing.body()).contains("RESOURCE_NOT_FOUND");
    }

    /** E2E-API-10: write-off, its request binding, and its refusal without a resolution action. */
    @Test
    @DisplayName("E2E-API-10: POST /reconciliation/cases/{id}/write-off binds both fields, "
            + "returns 204, and refuses a body missing the resolution action")
    void caseWriteOffIsBoundAndEnforcedOverHttp() throws Exception {
        String caseId = openCase("E2E-API-10");

        HttpResponse<String> incomplete = post("/api/v1/reconciliation/cases/" + caseId + "/write-off",
                ADMIN, "{\"resolutionNote\":\"the provider confirmed the missing event\"}");
        assertThat(incomplete.statusCode())
                .as("a write-off with no stated reason for writing it off is not a write-off; "
                        + "400 here is the binding working, not a broken route")
                .isEqualTo(400);
        assertThat(caseStatus(caseId))
                .as("and a refused body must leave the case exactly as it was")
                .isEqualTo("OPEN");

        HttpResponse<String> tooShort = post("/api/v1/reconciliation/cases/" + caseId + "/write-off",
                ADMIN, "{\"resolutionNote\":\"too short\",\"resolutionAction\":\"OTHER\"}");
        assertThat(tooShort.statusCode())
                .as("the note has a floor, so a write-off always carries something an auditor can read")
                .isEqualTo(400);

        HttpResponse<String> written = post("/api/v1/reconciliation/cases/" + caseId + "/write-off",
                ADMIN, "{\"resolutionNote\":\"the provider confirmed no capture was ever taken\","
                        + "\"resolutionAction\":\"PROVIDER_ERROR_CONFIRMED\"}");

        assertThat(written.statusCode())
                .as("body was: %s", written.body())
                .isEqualTo(204);
        assertThat(written.body())
                .as("204 carries no representation; returning one would be a second contract")
                .isEmpty();
        assertThat(caseStatus(caseId)).isEqualTo("WRITTEN_OFF");

        HttpResponse<String> missing =
                post("/api/v1/reconciliation/cases/case_missing/write-off", ADMIN,
                        "{\"resolutionNote\":\"a case that never existed was written off\","
                                + "\"resolutionAction\":\"OTHER\"}");
        assertThat(missing.statusCode()).isEqualTo(404);
    }

    /** E2E-API-11: the operations backlog is the only place a dead event stays visible. */
    @Test
    @DisplayName("E2E-API-11: GET /provider/events/failed lists events that exhausted their "
            + "attempts, and omits ones that are still retrying")
    void failedEventBacklogIsVisibleOverHttp() throws Exception {
        assertThat(get("/api/v1/provider/events/failed", OPERATOR).statusCode())
                .as("an empty backlog must still answer 200 with count 0 - operations polls this")
                .isEqualTo(200);

        seedFailedEvent("evt_dead_1", "payment.captured", 8, "provider event permanently refused");
        seedFailedEvent("evt_dead_2", "settlement.paid", 8, "provider event permanently refused");
        seedRetryingEvent("evt_retrying", "payment.captured", 2, "transient");

        HttpResponse<String> response = get("/api/v1/provider/events/failed", OPERATOR);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(jsonInt(response.body(), "count"))
                .as("body was: %s", response.body())
                .isEqualTo(2);
        assertThat(response.body())
                .as("and it must name them, including why they died - a backlog of ids alone "
                        + "sends an operator hunting")
                .contains("evt_dead_1")
                .contains("evt_dead_2")
                .contains("permanently refused");
        assertThat(response.body())
                .as("an event still retrying is not dead and must not be reported as one")
                .doesNotContain("evt_retrying");
    }

    // ------------------------------------------------------ surface-test helpers

    /** Creates a captured payment through the API and returns its PAYMENT_CAPTURE transaction id. */
    private String capturedPaymentTransaction() throws Exception {
        String body = """
                {"merchantReference":"MERCH-SURFACE","amount":{"amountMinor":10000,\
                "currency":"EGP"},"provider":"SIMULATED_PSP",\
                "providerTransactionId":"psp_surface_%s"}""".formatted(unique());
        String paymentId = jsonString(
                postWithKey("/api/v1/payments", OPERATOR, body, "surface-" + unique()).body(), "id");
        post("/api/v1/payments/" + paymentId + "/authorize", OPERATOR, "{}");
        post("/api/v1/payments/" + paymentId + "/capture", OPERATOR, "{}");

        return sql.sql("SELECT l.id FROM ledger_transactions l "
                        + "JOIN payment_ledger_transactions p ON p.ledger_transaction_id = l.id "
                        + "WHERE p.payment_id = ? AND p.role = 'CAPTURE'")
                .param(paymentId)
                .optional((rs, rowNum) -> rs.getString("id"))
                .orElseThrow(() -> new AssertionError("no capture posting for " + paymentId));
    }

    private String reversalPointsAt(String reversalId) {
        return sql.sql("SELECT reversal_of_transaction_id FROM ledger_transactions WHERE id = ?")
                .param(reversalId)
                .optional((rs, rowNum) -> rs.getString("reversal_of_transaction_id")).orElse(null);
    }

    private boolean originalStillPosted(String transactionId) {
        return sql.sql("SELECT state FROM ledger_transactions WHERE id = ?")
                .param(transactionId)
                .optional((rs, rowNum) -> "POSTED".equals(rs.getString("state"))).orElse(false);
    }

    /** Every entry of the reversal is the sign-flipped mirror of the original's. */
    private boolean entriesAreMirored(String originalId, String reversalId) {
        java.util.Map<String, String> original = entriesOf(originalId);
        java.util.Map<String, String> reversal = entriesOf(reversalId);
        if (original.isEmpty() || !original.keySet().equals(reversal.keySet())) {
            return false;
        }
        for (String account : original.keySet()) {
            String[] before = original.get(account).split(":");
            String[] after = reversal.get(account).split(":");
            if (!after[0].equals(flip(before[0])) || !after[1].equals(before[1])) {
                return false;
            }
        }
        return true;
    }

    private java.util.Map<String, String> entriesOf(String transactionId) {
        java.util.Map<String, String> byAccount = new java.util.LinkedHashMap<>();
        sql.sql("SELECT a.code, e.direction, e.amount_minor FROM ledger_entries e "
                        + "JOIN ledger_accounts a ON a.id = e.account_id WHERE e.transaction_id = ?")
                .param(transactionId)
                .list((rs, rowNum) -> {
                    byAccount.put(rs.getString("code"),
                            rs.getString("direction") + ":" + rs.getLong("amount_minor"));
                    return rs.getString("code");
                });
        return byAccount;
    }

    private static String flip(String direction) {
        return "DEBIT".equals(direction) ? "CREDIT" : "DEBIT";
    }

    /** Seeds one open case, with the batch and result rows it must reference. */
    private String openCase(String reference) {
        String batchId = "rbat_" + reference;
        String resultId = "rres_" + reference;
        String caseId = "rcase_" + reference;

        sql.sql("""
                INSERT INTO reconciliation_batches
                    (id, provider, window_start, window_end, subject_mode, status, started_by,
                     request_id)
                VALUES (?, 'SIMULATED_PSP', now() - interval '1 day', now(), 'BOTH', 'COMPLETED',
                        'surface-test', 'req_surface')
                """).param(batchId).update();
        sql.sql("""
                INSERT INTO reconciliation_results (id, batch_id, subject_key, outcome)
                VALUES (?, ?, ?, 'MISSING_ON_PROVIDER')
                """).params(resultId, batchId, "SIMULATED_PSP:psp_" + reference).update();
        sql.sql("""
                INSERT INTO reconciliation_cases
                    (id, batch_id, result_id, reason, severity, status)
                VALUES (?, ?, ?, 'MISSING_ON_PROVIDER', 'MEDIUM', 'OPEN')
                """).params(caseId, batchId, resultId).update();
        return caseId;
    }

    private String caseStatus(String caseId) {
        return sql.sql("SELECT status FROM reconciliation_cases WHERE id = ?")
                .param(caseId)
                .optional((rs, rowNum) -> rs.getString("status")).orElse(null);
    }/** Puts a dead event straight into the backlog the endpoint exists to report. */
    private void seedFailedEvent(String eventId, String eventType, int attempts, String error) {
        seedEvent(eventId, eventType, attempts, error, "FAILED", 0);
    }

    /**
     * An event that failed once and is backing off — {@code PENDING}, not {@code FAILED}.
     *
     * <p>The endpoint filters on status, not on the attempt count, which is the right filter: an
     * event that has used two of its eight attempts is not dead, and listing it in a backlog an
     * operator is meant to clear would be crying wolf.
     */
    private void seedRetryingEvent(String eventId, String eventType, int attempts, String error) {
        seedEvent(eventId, eventType, attempts, error, "PENDING", 30);
    }

    private void seedEvent(String eventId, String eventType, int attempts, String error,
            String status, long nextAttemptInSeconds) {
        sql.sql("""
                INSERT INTO provider_events
                    (id, provider, event_id, event_type, provider_occurred_at, payload, payload_hash,
                     status, attempts, last_error, next_attempt_at)
                VALUES (?, 'SIMULATED_PSP', ?, ?, now(),
                jsonb_build_object('type', ?), repeat('a', 64), ?, ?, ?,
                now() + (? * interval '1 second'))
                """).params("pev_" + eventId, eventId, eventType, eventType, status, attempts,
                        error, nextAttemptInSeconds)
                .update();
    }

    private int jsonInt(String json, String field) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*(\\d+)").matcher(json);
        if (!matcher.find()) {
            throw new AssertionError("no numeric field " + field + " in " + json);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private String jsonString(String json, String field) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (!matcher.find()) {
            throw new AssertionError("no field " + field + " in " + json);
        }
        return matcher.group(1);
    }

    /** The payment's state, read back from the database rather than from the response body. */
    private String paymentState(String paymentId) {
        return sql.sql("SELECT state FROM payments WHERE id = ?")
                .param(paymentId)
                .optional((rs, rowNum) -> rs.getString("state"))
                .orElse(null);
    }

    private String unique() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    // ------------------------------------------------------------------ helpers

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .POST(body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Posts with an idempotency key.
     *
     * <p>A separate helper rather than an overload because {@link #post} deliberately omits the
     * header: the missing-key refusal is one of the behaviours this class proves, and a shared
     * helper that sometimes sent a key would make that proof depend on its caller.
     */
    private HttpResponse<String> postWithKey(String path, String token, String body, String key)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Sends a hand-written request and returns the raw response text.
     *
     * <p>Exists for the one case the JDK client cannot express: a header value containing a
     * control character, which {@link HttpClient} rejects locally. Asserting on that rejection
     * would test the JDK rather than this application.
     */
    private String rawRequest(String requestText) throws Exception {
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
            socket.getOutputStream().write(requestText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** Case-insensitive header lookup on a raw HTTP response. */
    private String headerValue(String response, String header) {
        for (String line : response.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(header)) {
                return line.substring(colon + 1).trim();
            }
        }
        return "";
    }

    private int paymentsFor(String merchantReference) {
        return sql.sql("SELECT count(*) AS n FROM payments WHERE merchant_reference = ?")
                .param(merchantReference)
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }

    // ------------------------------------------------- regressions from the V&V audit
    //
    // Each of these pins a defect found by an independent audit against the running image while
    // this suite was fully green. They are collected here rather than scattered so that the reason
    // each one exists is legible in one place: the common thread is that every one of them was
    // reachable through the public API and none of them was reachable through a test.

    @Test
    @DisplayName("AUDIT-01: an unknown provider is refused, not stored")
    void unknownProviderIsRefused() throws Exception {
        // The guard ran only when `provider` was blank, so a non-blank unknown value never reached
        // it: the request was accepted 201 and persisted under a provider no batch filters on. The
        // payment could then never reconcile and nothing would ever say so.
        HttpResponse<String> response = postWithKey("/api/v1/payments", OPERATOR,
                "{\"merchantReference\":\"MERCH-" + unique() + "\","
                        + "\"amount\":{\"amountMinor\":10000,\"currency\":\"EGP\"},"
                        + "\"provider\":\"TOTALLY_NOT_A_PROVIDER\"}",
                "audit-provider-" + unique());

        assertThat(response.statusCode())
                .as("an unknown provider is a refusal the caller can fix, not a 201")
                .isEqualTo(400);
        assertThat(response.body()).contains("VALIDATION_FAILED");
        assertThat(sql.sql("SELECT count(*) AS n FROM payments WHERE provider = ?")
                .param("TOTALLY_NOT_A_PROVIDER")
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0))
                .as("nothing may be persisted under a provider this deployment does not know")
                .isZero();
    }

    @Test
    @DisplayName("AUDIT-02: a known provider is still accepted, and an absent one defaults")
    void knownAndAbsentProvidersStillWork() throws Exception {
        assertThat(postWithKey("/api/v1/payments", OPERATOR,
                "{\"merchantReference\":\"MERCH-" + unique() + "\","
                        + "\"amount\":{\"amountMinor\":10000,\"currency\":\"EGP\"},"
                        + "\"provider\":\"SIMULATED_PSP\"}",
                "audit-known-" + unique()).statusCode())
                .isEqualTo(201);

        assertThat(postWithKey("/api/v1/payments", OPERATOR,
                "{\"merchantReference\":\"MERCH-" + unique() + "\","
                        + "\"amount\":{\"amountMinor\":10000,\"currency\":\"EGP\"}}",
                "audit-absent-" + unique()).statusCode())
                .as("an absent provider means the caller did not care")
                .isEqualTo(201);
    }

    @Test
    @DisplayName("AUDIT-03: merchantReference is validated as a 400, never as a 500")
    void merchantReferenceIsValidated() throws Exception {
        String tooLong = "M".repeat(100);
        HttpResponse<String> overLength = postWithKey("/api/v1/payments", OPERATOR,
                "{\"merchantReference\":\"" + tooLong + "\","
                        + "\"amount\":{\"amountMinor\":10000,\"currency\":\"EGP\"}}",
                "audit-long-" + unique());

        // The VARCHAR(64) column used to be the only validator, so an over-length value became a
        // SQL error and therefore a 500 — a rejected business decision reported as a server fault,
        // which is what makes clients escalate and retry something that can never succeed.
        assertThat(overLength.statusCode())
                .as("spec 06 §4: never 500 for a rejected business decision")
                .isEqualTo(400);
        assertThat(overLength.body()).contains("VALIDATION_FAILED");

        HttpResponse<String> illegal = postWithKey("/api/v1/payments", OPERATOR,
                "{\"merchantReference\":\"bad ref!@#$\","
                        + "\"amount\":{\"amountMinor\":10000,\"currency\":\"EGP\"}}",
                "audit-chars-" + unique());
        assertThat(illegal.statusCode())
                .as("spec 02 §6: ^[A-Za-z0-9_-]{1,64}$ — characters outside it are refused")
                .isEqualTo(400);

        assertThat(postWithKey("/api/v1/payments", OPERATOR,
                "{\"merchantReference\":\"MERCH_ok-123\","
                        + "\"amount\":{\"amountMinor\":10000,\"currency\":\"EGP\"}}",
                "audit-ok-" + unique()).statusCode())
                .as("a conforming reference is still accepted")
                .isEqualTo(201);
    }

    @Test
    @DisplayName("AUDIT-04: an audit row carries the request id, not the actor id")
    void auditRowsAreCorrelatedToTheRequest() throws Exception {
        String reference = "MERCH-" + unique();
        HttpResponse<String> created = postWithKey("/api/v1/payments", OPERATOR,
                "{\"merchantReference\":\"" + reference + "\","
                        + "\"amount\":{\"amountMinor\":10000,\"currency\":\"EGP\"}}",
                "audit-corr-" + unique());
        String paymentId = jsonString(created.body(), "id");

        HttpResponse<String> authorized = post("/api/v1/payments/" + paymentId + "/authorize",
                OPERATOR, null);
        HttpResponse<String> captured = post("/api/v1/payments/" + paymentId + "/capture",
                OPERATOR, "{\"expectedAmount\":{\"amountMinor\":10000,\"currency\":\"EGP\"}}");
        assertThat(captured.statusCode()).as("the payment is captured").isEqualTo(200);

        // authorize, capture and refund went through applyTransition, which passed the ACTOR id
        // into audit.record's requestId slot. Every one of those rows therefore read "api" and the
        // three most important financial transitions could not be tied back to their request. Each
        // row is checked against the id echoed by the request that wrote it - not against a single
        // shared id, which would pass even if the rows carried each other's request ids.
        assertCorrelated("PAYMENT_CREATED", paymentId, created);
        assertCorrelated("PAYMENT_AUTHORIZED", paymentId, authorized);
        assertCorrelated("PAYMENT_CAPTURED", paymentId, captured);
    }

    // ------------------------------------------------------------------ F-05

    @Test
    @DisplayName("F-05: a capture expectation in the wrong currency is refused, not accepted")
    void captureExpectationComparesTheWholeMonetaryValue() throws Exception {
        String reference = "MERCH-" + unique();
        HttpResponse<String> created = postWithKey("/api/v1/payments", OPERATOR,
                "{\"merchantReference\":\"" + reference + "\","
                        + "\"amount\":{\"amountMinor\":20000,\"currency\":\"EGP\"}}",
                "capture-fx-" + unique());
        String paymentId = jsonString(created.body(), "id");
        assertThat(post("/api/v1/payments/" + paymentId + "/authorize", OPERATOR, null)
                .statusCode()).isEqualTo(200);

        // Same magnitude, different currency. The guard compared amountMinor only, so this
        // returned 200 and captured the EGP payment — the caller was told its USD expectation was
        // satisfied by an EGP capture. The internal posting stayed in EGP, which is why no money
        // moved wrongly; the defect is that the API accepted an assertion about money it was not
        // asserting.
        HttpResponse<String> wrongCurrency = post("/api/v1/payments/" + paymentId + "/capture",
                OPERATOR, "{\"expectedAmount\":{\"amountMinor\":20000,\"currency\":\"USD\"}}");

        assertThat(wrongCurrency.statusCode())
                .as("200 EGP is not 200 USD, and the guard's own contract is 'must equal the "
                        + "authorised amount'")
                .isEqualTo(409);
        assertThat(wrongCurrency.body()).contains("CAPTURE_AMOUNT_MISMATCH");

        assertThat(paymentState(paymentId))
                .as("a refused capture must leave the payment where it was")
                .isEqualTo("AUTHORIZED");

        // The control: the same figure in the right currency is accepted.
        HttpResponse<String> rightCurrency = post("/api/v1/payments/" + paymentId + "/capture",
                OPERATOR, "{\"expectedAmount\":{\"amountMinor\":20000,\"currency\":\"EGP\"}}");
        assertThat(rightCurrency.statusCode()).isEqualTo(200);
        assertThat(paymentState(paymentId)).isEqualTo("CAPTURED");
    }

    private void assertCorrelated(String action, String paymentId, HttpResponse<String> response) {
        String correlationId = response.headers().firstValue("X-Request-Id").orElse("");
        assertThat(correlationId).as("every response carries a correlation id").isNotBlank();

        assertThat(sql.sql("SELECT request_id FROM audit_events WHERE action = ? "
                        + "AND entity_id = ? ORDER BY occurred_at DESC LIMIT 1")
                .params(action, paymentId)
                .optional((rs, rowNum) -> rs.getString("request_id")).orElse(null))
                .as(action + " must carry the correlation id of its own request")
                .isEqualTo(correlationId);
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }
}
