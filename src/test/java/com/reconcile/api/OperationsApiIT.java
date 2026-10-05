package com.reconcile.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.support.SharedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The operational surfaces: {@code /health}, the audit read API and the metrics summary.
 *
 * <p>Maps to {@code I-LED-05} and the operations rows of {@code docs/spec/06 §1}. The property under
 * test is that a health endpoint means something: it returns {@code 503} when the system's own
 * invariants are broken, and it names the check that failed. A health endpoint that answers
 * {@code 200} because the process is alive is worse than none — a load balancer will keep routing
 * traffic to a ledger that does not verify, and the status page stays green throughout.
 *
 * <p>The balance corruption below is written from raw SQL with the guard trigger disabled. That is
 * not something the application can do, which is exactly the point: it simulates a restore from
 * backup, a manual fix, or a bug in something that writes through a different path — the cases L7
 * exists to catch, and the cases no amount of application-level testing would ever produce.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OperationsApiIT {

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
        registry.add("reconcile.provider.webhook-secret", () -> "webhook-secret-token");
        registry.add("reconcile.reconciliation.lease", () -> "30m");
        // Essential here rather than merely tidy: the tests below insert batches that are
        // deliberately left RUNNING. ReconciliationWorker polls RUNNING batches every 2s, so
        // without this the scheduler would complete `rbat_stuck` — the row the abandoned-batch
        // health check exists to report — before the assertion ran.
        registry.add("reconcile.reconciliation.poll-interval", () -> "1h");
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

    // ------------------------------------------------------------------ health

    @Test
    @DisplayName("I-HEALTH-01: a healthy system answers 200 with every check named")
    void healthySystemIsUp() throws Exception {
        HttpResponse<String> response = get("/api/v1/health", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
        assertThat(response.body())
                .as("the checks are named, so an operator can see what was actually examined")
                .contains("migrations", "ledger", "reconciliation");
        assertThat(response.body()).doesNotContain("FAIL");
    }

    @Test
    @DisplayName("health is unauthenticated: a load balancer has no bearer token to present")
    void healthNeedsNoToken() throws Exception {
        assertThat(get("/api/v1/health", null).statusCode())
                .as("this route is permitAll by design (06 §6); a 401 here would take the health "
                        + "check out of service")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("I-LED-05: a balance edited out of band makes health 503 and names the drift")
    void corruptedBalanceIsReportedAsUnhealthy() throws Exception {
        assertThat(get("/api/v1/health", null).statusCode()).isEqualTo(200);

        // The application cannot do this. Disabling the guard first is the only way to write a
        // balance that disagrees with its entries, which is exactly what a bad restore looks like.
        sql.sql("ALTER TABLE account_balances DISABLE TRIGGER tg_asset_non_negative").update();
        sql.sql("""
                UPDATE account_balances b
                   SET balance_minor = b.balance_minor + 4242
                  FROM ledger_accounts a
                 WHERE a.id = b.account_id AND a.code = 'PSP_CLEARING' AND b.currency = 'EGP'
                """).update();
        sql.sql("ALTER TABLE account_balances ENABLE TRIGGER tg_asset_non_negative").update();

        HttpResponse<String> response = get("/api/v1/health", null);

        assertThat(response.statusCode())
                .as("L7 failed, so the system is not healthy whatever else is true")
                .isEqualTo(503);
        assertThat(response.body()).contains("\"status\":\"DOWN\"");
        assertThat(response.body()).contains("\"name\":\"ledger\"", "\"status\":\"FAIL\"");
        assertThat(response.body())
                .as("the failing check names the account and both figures, or nobody can act on it")
                .contains("PSP_CLEARING", "4242");
        assertThat(response.body())
                .as("the checks that did pass are still reported, so the operator can see this is "
                        + "one problem rather than an outage")
                .contains("\"name\":\"migrations\",\"status\":\"PASS\"");
    }

    @Test
    @DisplayName("A batch stuck RUNNING past its lease is reported, because nobody else would notice")
    void abandonedBatchIsReported() throws Exception {
        sql.sql("""
                INSERT INTO reconciliation_batches
                    (id, provider, window_start, window_end, subject_mode, status, started_by,
                     request_id, started_at)
                VALUES ('rbat_stuck', 'SIMULATED_PSP', now() - interval '2 days',
                        now() - interval '1 day', 'BOTH', 'RUNNING', 'test', 'req_x',
                        now() - interval '2 days')
                """).update();

        HttpResponse<String> response = get("/api/v1/health", null);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("rbat_stuck");
        assertThat(response.body()).contains("\"name\":\"reconciliation\"");
    }

    @Test
    @DisplayName("A batch still inside its lease is not reported as abandoned")
    void runningBatchInsideItsLeaseIsNotAFailure() throws Exception {
        sql.sql("""
                INSERT INTO reconciliation_batches
                    (id, provider, window_start, window_end, subject_mode, status, started_by,
                     request_id, started_at)
                VALUES ('rbat_running', 'SIMULATED_PSP', now() - interval '2 hours',
                        now() + interval '1 hour', 'BOTH', 'RUNNING', 'test', 'req_y', now())
                """).update();

        assertThat(get("/api/v1/health", null).statusCode())
                .as("a batch that is simply working is not a failure")
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------ audit

    @Test
    @DisplayName("I-AUDIT-01: the trail for one payment is complete, newest first and linked")
    void paymentTrailIsComplete() throws Exception {
        String paymentId = createPayment();

        HttpResponse<String> response = get("/api/v1/audit/entities/PAYMENT/" + paymentId, OPERATOR);

        assertThat(response.statusCode()).isEqualTo(200);
        List<String> actions = actionsIn(response.body());
        assertThat(actions)
                .as("created, authorized and captured, in reverse chronological order")
                .containsExactly("PAYMENT_CAPTURED", "PAYMENT_AUTHORIZED", "PAYMENT_CREATED");
        assertThat(response.body())
                .as("every row carries the correlation id, which is what makes the trail joinable")
                .contains("\"requestId\"");
    }

    @Test
    @DisplayName("audit events can be filtered by action, entity and request id")
    void auditEventsAreFilterable() throws Exception {
        String paymentId = createPayment();

        HttpResponse<String> byEntity = get(
                "/api/v1/audit/events?entityType=PAYMENT&entityId=" + paymentId, OPERATOR);
        assertThat(actionsIn(byEntity.body()))
                .containsExactly("PAYMENT_CAPTURED", "PAYMENT_AUTHORIZED", "PAYMENT_CREATED");

        HttpResponse<String> byAction = get(
                "/api/v1/audit/events?action=PAYMENT_CREATED", OPERATOR);
        assertThat(actionsIn(byAction.body())).containsExactly("PAYMENT_CREATED");

        HttpResponse<String> byNothing = get("/api/v1/audit/events?action=NOTHING_MATCHES", OPERATOR);
        assertThat(actionsIn(byNothing.body()))
                .as("a filter that matches nothing returns an empty list, not everything")
                .isEmpty();
    }

    @Test
    @DisplayName("A keyset page returns the same rows as OFFSET would, without skipping any")
    void keysetPagingDoesNotSkipOrRepeat() throws Exception {
        for (int i = 0; i < 5; i++) {
            createPayment();
        }

        HttpResponse<String> first = get("/api/v1/audit/events?limit=2", OPERATOR);
        List<String> firstPageIds = stringsIn(first.body(), "id");
        assertThat(firstPageIds).hasSize(2);

        // The cursor is the *last* row of page 1, not the first: the response is newest first, so
        // the next page starts below the oldest row already delivered.
        String lastId = firstPageIds.get(1);
        HttpResponse<String> second = get(
                "/api/v1/audit/events?limit=2&after=" + instantOf(lastId) + "&afterId=" + lastId,
                OPERATOR);

        List<String> secondPageIds = stringsIn(second.body(), "id");
        assertThat(secondPageIds)
                .as("a second page continues exactly where the first stopped")
                .hasSize(2);
        assertThat(secondPageIds)
                .as("and never repeats a row from the first")
                .doesNotContainAnyElementsOf(firstPageIds);
    }

    @Test
    @DisplayName("Every entity type the application writes is servable by the entity-trail route")
    void entityTrailCoversEveryTypeTheApplicationWrites() throws Exception {
        // The whitelist in AuditQueryService had drifted from reality: it listed PAYOUT,
        // LEDGER_TRANSACTION and ACCOUNT, which nothing ever writes, and omitted PAYOUT_RECORD and
        // PROVIDER, which are. So `/audit/entities/PAYOUT_RECORD/{id}` was a 404 for a payout that
        // had genuinely happened, while three impossible types returned a confident empty list.
        //
        // This reads the types straight out of the database and asks the route about each one, so
        // the next divergence fails here rather than in an operator's browser. The payment is what
        // writes them: `audit_events` is truncated per test, so without it the loop below would
        // assert nothing at all and pass vacuously.
        createPayment();
        List<String> written = sql.sql("""
                SELECT DISTINCT entity_type FROM audit_events ORDER BY entity_type
                """).list((rs, i) -> rs.getString("entity_type"));

        assertThat(written).as("something wrote audit events at all").isNotEmpty();
        for (String entityType : written) {
            HttpResponse<String> response =
                    get("/api/v1/audit/entities/" + entityType + "/x", OPERATOR);
            assertThat(response.statusCode())
                    .as("entity type %s is written but the route refuses it", entityType)
                    .isEqualTo(200);
        }

        // And the lowercase form documented in spec 06 resolves to the same thing, because the
        // stored casing is a storage detail and not part of the contract.
        assertThat(get("/api/v1/audit/entities/payment/x", OPERATOR).statusCode())
                .as("case-insensitive, as the documented example requires")
                .isEqualTo(200);

        // Case-insensitivity is not aliasing. `payments` is the plural noun, not another casing
        // of the token `PAYMENT`, so it is an unknown type like any other.
        assertThat(get("/api/v1/audit/entities/payments/x", OPERATOR).statusCode())
                .as("a plural is not a casing of the singular token")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("An unknown entity type is a 404, not an empty list or a 500")
    void unknownEntityTypeIsNotFound() throws Exception {
        HttpResponse<String> response = get("/api/v1/audit/entities/NOT_A_TYPE/x", OPERATOR);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("The audit API is operator-only")
    void auditApiRequiresAToken() throws Exception {
        assertThat(get("/api/v1/audit/events", null).statusCode()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ metrics

    @Test
    @DisplayName("The summary counts what an operator asks about first, and nothing is cached")
    void summaryCountsTheLiveSystem() throws Exception {
        createPayment();
        createPayment();

        HttpResponse<String> response = get("/api/v1/metrics/summary", OPERATOR);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"paymentsByState\"");
        assertThat(response.body()).contains("\"CAPTURED\":2");
        assertThat(response.body()).contains("\"openCases\":0");
        assertThat(response.body())
                .as("the figure an operator acts on is present even when it is zero")
                .contains("\"unreconciledDeltaMinor\":0");
    }

    // ------------------------------------------------------------------ helpers

    private String createPayment() throws Exception {
        String key = "ops-" + java.util.UUID.randomUUID();
        String body = """
                {"merchantReference":"MERCH-OPS","amount":{"amountMinor":10000,"currency":"EGP"},\
                "provider":"SIMULATED_PSP"}""";
        HttpResponse<String> created = post("/api/v1/payments", OPERATOR, key, body);
        assertThat(created.statusCode()).as("create failed: %s", created.body()).isEqualTo(201);
        String paymentId = firstStringIn(created.body(), "id");

        post("/api/v1/payments/" + paymentId + "/authorize", OPERATOR,
                "ops-" + java.util.UUID.randomUUID(), "{}");
        post("/api/v1/payments/" + paymentId + "/capture", OPERATOR,
                "ops-" + java.util.UUID.randomUUID(), "{}");
        return paymentId;
    }

    private List<String> actionsIn(String json) {
        return stringsIn(json, "action");
    }

    /**
     * Every value of one string field, in document order.
     *
     * <p>A hand-rolled scan rather than a JSON library or a regex. The responses are read to assert
     * on structure, and a helper that can itself fail to parse would turn a real defect into a
     * confusing error in the wrong place.
     */
    private List<String> stringsIn(String json, String field) {
        List<String> values = new java.util.ArrayList<>();
        char quote = 34;
        String needle = String.valueOf((char) quote) + field + (char) quote;
        int from = 0;
        while (true) {
            int at = json.indexOf(needle, from);
            if (at < 0) {
                return values;
            }
            // Skip the name, the colon and any whitespace to reach the opening quote of the value.
            // Searching for the next quote instead would pick up whichever field follows this one.
            int i = at + needle.length();
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
                i++;
            }
            if (i >= json.length() || json.charAt(i) != ':') {
                from = at + needle.length();
                continue;
            }
            i++;
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
                i++;
            }
            if (i >= json.length() || json.charAt(i) != quote) {
                from = at + needle.length();
                continue;
            }
            int close = json.indexOf(quote, i + 1);
            if (close < 0) {
                return values;
            }
            values.add(json.substring(i + 1, close));
            from = close + 1;
        }
    }

    private String firstStringIn(String json, String field) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("no field " + field + " in " + json);
        }
        return matcher.group(1);
    }

    private String instantOf(String auditEventId) {
        return sql.sql("SELECT occurred_at FROM audit_events WHERE id = ?")
                .param(auditEventId)
                .optional((rs, rowNum) -> Sql.instant(rs, "occurred_at").toString())
                .orElse(Instant.EPOCH.toString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create(base() + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token, String key, String body)
            throws Exception {

        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }
}
