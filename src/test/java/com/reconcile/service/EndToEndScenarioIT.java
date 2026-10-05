package com.reconcile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.simulator.SimulatedProviderService;
import com.reconcile.support.SharedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
 * The four demonstrations in the README, as tests.
 *
 * <p>If one of these fails, the README's claim is false, and this file says so. They run over real
 * HTTP against a real PostgreSQL with the simulated provider in the loop, using the same endpoints
 * a reader of the README would call — no service is invoked directly, because a demonstration that
 * skips the API is a demonstration of something else.
 *
 * <p>{@code E2E-DEMO-02} is the one worth the most attention: the money is wrong, the engine says
 * so, a human resolves the case, and re-running the reconciliation must <b>not</b> open a second
 * case. That last part is the whole operational story — a system that opens a new case for every
 * re-run is deleted by its users within a week.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndToEndScenarioIT {

    private static final String OPERATOR = "operator-secret-token";
    private static final String ADMIN = "admin-secret-token";


    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    Sql sql;

    @Autowired
    InboundEventWorker worker;

    @Autowired
    ReconciliationWorker reconciliationWorker;

    @Autowired
    SimulatedProviderService simulator;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedPostgres::jdbcUrl);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("reconcile.security.operator-token", () -> OPERATOR);
        registry.add("reconcile.security.admin-token", () -> ADMIN);
        registry.add("reconcile.provider.webhook-secret", () -> "webhook-secret-token");
        registry.add("reconcile.provider.event-poll-interval", () -> "1h");
        registry.add("reconcile.provider.sync-base-url", () -> "http://127.0.0.1:" + 58_101);
        registry.add("reconcile.provider.sync-timeout", () -> "1s");
    }

    @BeforeEach
    void reset() {
        simulator.reset();
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

    // ------------------------------------------------------------------ E2E-DEMO-01

    @Test
    @DisplayName("E2E-DEMO-01: create, capture, settle, reconcile, payout - and only the fee is left")
    void theHappyPathEndsWithNothingButTheFee() throws Exception {
        String externalId = registerWithTheProvider("MERCH-1", List.of());
        String paymentId = createPayment(externalId);
        authorize(paymentId);
        capture(paymentId);

        assertThat(balanceOf("PSP_CLEARING")).isEqualTo(10_000L);
        assertThat(balanceOf("MERCHANT_PAYABLE")).isEqualTo(9_700L);

        deliverEverything(externalId);
        worker.processBatch(50);

        assertThat(stateOf(paymentId))
                .as("the provider's settlement moved the payment to SETTLED")
                .isEqualTo("SETTLED");
        assertThat(balanceOf("PSP_CLEARING"))
                .as("and the money out of clearing")
                .isZero();
        assertThat(balanceOf("PLATFORM_CASH")).isEqualTo(10_000L);

        String batchId = runBatchInline();
        assertThat(balanceOf("PLATFORM_CASH"))
                .as("a reconciliation batch never posts; it only compares")
                .isEqualTo(10_000L);
        assertThat(outcomesIn(batchId)).containsExactly("MATCHED");
        assertThat(openCaseCount()).isZero();

        HttpResponse<String> payout = post("/api/v1/payouts", ADMIN,
                "po-" + unique(), """
                        {"merchantReference":"MERCH-1","paymentIds":["%s"]}""".formatted(paymentId));
        assertThat(payout.statusCode()).isEqualTo(201);

        assertThat(balanceOf("PLATFORM_CASH"))
                .as("after the payout the platform holds exactly its fee")
                .isEqualTo(300L);
        assertThat(balanceOf("PLATFORM_FEE_REVENUE")).isEqualTo(300L);
        assertThat(balanceOf("MERCHANT_PAYABLE")).isZero();
        assertThat(balanceOf("PSP_CLEARING")).isZero();
        // Balances, not entry counts: the ledger is append-only by design (L4), so the entries for
        // clearing and the payable are still there - the claim is that nothing is left *owed*.
        assertThat(ledgerTransactionsOfType("PAYMENT_CAPTURE")).isEqualTo(1);
        assertThat(ledgerTransactionsOfType("SETTLEMENT_RECEIVED")).isEqualTo(1);
        assertThat(ledgerTransactionsOfType("MERCHANT_PAYOUT")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ E2E-DEMO-02

    @Test
    @DisplayName("E2E-DEMO-02: a wrong amount becomes a case, a human resolves it, and it stays resolved")
    void aDiscrepancyIsFoundResolvedAndNotReopened() throws Exception {
        // WRONG_AMOUNT: the provider reports 97.00 where we posted 100.00.
        String externalId = registerWithTheProvider("MERCH-2", List.of("WRONG_AMOUNT"));
        String paymentId = createPayment(externalId);
        authorize(paymentId);
        capture(paymentId);
        deliverEverything(externalId);
        worker.processBatch(50);

        String batchId = runBatchInline();

        assertThat(outcomesIn(batchId)).containsExactly("AMOUNT_MISMATCH");
        assertThat(deltaOf(externalId))
                .as("the 3.00 the provider is short, recorded exactly")
                .isEqualTo(300L);

        String caseId = firstCaseId();
        assertThat(statusOf("SELECT status FROM reconciliation_cases WHERE id = ?", caseId))
                .isEqualTo("OPEN");
        assertThat(sql.sql("SELECT count(*) AS n FROM reconciliation_cases")
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0)).isEqualTo(1);

        HttpResponse<String> withoutNote = post("/api/v1/reconciliation/cases/" + caseId
                + "/resolve", ADMIN, "resolve-" + unique(), """
                {"resolutionNote":"fixed"}""");
        assertThat(withoutNote.statusCode())
                .as("a four-character note is a malformed request (400), not a business refusal")
                .isEqualTo(400);

        HttpResponse<String> bogusAdjustment = post("/api/v1/reconciliation/cases/" + caseId
                + "/resolve", ADMIN, "resolve-" + unique(), """
                {"resolutionNote":"the PSP applied a processing fee our policy did not model; \
                confirmed with the provider",
                 "adjustmentLedgerTransactionId":"ltx_does_not_exist"}""");
        assertThat(bogusAdjustment.statusCode())
                .as("a well-formed request naming an adjustment that does not exist is a 422")
                .isEqualTo(422);
        assertThat(bogusAdjustment.body()).contains("CASE_RESOLUTION_INCOMPLETE");

        HttpResponse<String> resolved = post("/api/v1/reconciliation/cases/" + caseId + "/resolve",
                ADMIN, "resolve-" + unique(), """
                {"resolutionNote":"the PSP applied a processing fee our policy did not model; \
                confirmed with the provider and the fee schedule updated"}""");
        assertThat(resolved.statusCode()).isEqualTo(204);
        assertThat(statusOf("SELECT status FROM reconciliation_cases WHERE id = ?", caseId))
                .isEqualTo("RESOLVED");

        // The decisive step: reconcile the same window again. The money is still wrong, so the
        // result is still a mismatch - but the operator already dealt with it, and a second case
        // would mean they are asked about it every night forever.
        String rerun = runBatchInline();
        assertThat(outcomesIn(rerun))
                .as("the discrepancy is still there, and still reported")
                .containsExactly("AMOUNT_MISMATCH");
        assertThat(sql.sql("SELECT count(*) AS n FROM reconciliation_cases")
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0))
                .as("but it is one case, with its occurrence count raised instead")
                .isEqualTo(1);
        assertThat(sql.sql("SELECT occurrence_count AS n FROM reconciliation_cases WHERE id = ?")
                .param(caseId).optional((rs, rowNum) -> rs.getInt("n")).orElse(0)).isEqualTo(2);
        assertThat(statusOf("SELECT status FROM reconciliation_cases WHERE id = ?", caseId))
                .as("and a resolved case is not dragged back into an open one by a re-run")
                .isEqualTo("RESOLVED");
        assertThat(openCaseCount()).isZero();
    }

    // ------------------------------------------------------------------ E2E-DEMO-03

    @Test
    @DisplayName("E2E-DEMO-03: one webhook delivered three times changes the world once")
    void threeDeliveriesOneEffect() throws Exception {
        String externalId = registerWithTheProvider("MERCH-3", List.of());
        String paymentId = createPayment(externalId);
        authorize(paymentId);

        SimulatedProviderService.QueuedEvent captured = queuedEvents(externalId).stream()
                .filter(event -> event.body().contains("payment.captured"))
                .findFirst()
                .orElseThrow();

        List<Integer> statuses = List.of(
                deliver(captured).statusCode(),
                deliver(captured).statusCode(),
                deliver(captured).statusCode());
        worker.processBatch(50);

        assertThat(statuses)
                .as("one acceptance and two acknowledgements: a duplicate is a success, not a fault")
                .containsExactly(202, 200, 200);
        assertThat(deliveriesFor(captured.eventId()))
                .as("three deliveries are visible as three, whatever the effect was")
                .isEqualTo(3);
        assertThat(eventRows(captured.eventId())).isEqualTo(1);
        assertThat(sql.sql("""
                SELECT count(*) AS n FROM payment_state_history
                 WHERE payment_id = ? AND to_state = 'CAPTURED'
                """).param(paymentId).optional((rs, rowNum) -> rs.getInt("n")).orElse(0))
                .as("one state change")
                .isEqualTo(1);
        assertThat(ledgerTransactionsOfType("PAYMENT_CAPTURE"))
                .as("and one posting")
                .isEqualTo(1);
        assertThat(balanceOf("PSP_CLEARING")).isEqualTo(10_000L);
        assertThat(sql.sql("SELECT count(*) AS n FROM audit_events WHERE action = 'WEBHOOK_ACCEPTED'")
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ E2E-DEMO-04

    @Test
    @DisplayName("E2E-DEMO-04: a batch interrupted and restarted still produces one answer per subject")
    void anInterruptedBatchResumesWithoutDuplicating() throws Exception {
        for (int i = 0; i < 12; i++) {
            String externalId = registerWithTheProvider("MERCH-" + i, List.of());
            String paymentId = createPayment(externalId);
            authorize(paymentId);
            capture(paymentId);
        }

        String batchId = createBatch();
        reconciliationWorker.runBatch(batchId);
        int afterFirstRun = resultCount(batchId);
        reconciliationWorker.runBatch(batchId);

        assertThat(afterFirstRun).isEqualTo(12);
        assertThat(resultCount(batchId))
                .as("re-running a completed batch adds nothing: results are keyed and immutable")
                .isEqualTo(12);
        assertThat(sql.sql("SELECT processed_subjects AS n FROM reconciliation_batches WHERE id = ?")
                .param(batchId).optional((rs, rowNum) -> rs.getInt("n")).orElse(-1))
                .as("and the batch totals still agree with the rows")
                .isEqualTo(12);
        assertThat(statusOf("SELECT status FROM reconciliation_batches WHERE id = ?", batchId))
                .isEqualTo("COMPLETED");
        assertThat(ledgerTransactionsOfType("PAYMENT_CAPTURE")).isEqualTo(12);
        assertThat(balanceOf("PSP_CLEARING")).isEqualTo(120_000L);
    }

    // ------------------------------------------------------------------ HTTP helpers

    private String registerWithTheProvider(String merchant, List<String> behaviours) throws Exception {
        String body = """
                {"merchantReference":"%s","grossAmountMinor":10000,"currency":"EGP","behaviour":%s}"""
                .formatted(merchant, jsonArray(behaviours));
        HttpResponse<String> response = post("/api/v1/provider/payments", OPERATOR,
                "reg-" + unique(), body);
        assertThat(response.statusCode())
                .as("registering with the provider failed: %s", response.body())
                .isEqualTo(201);
        return jsonString(response.body(), "externalTransactionId");
    }

    private String createPayment(String externalId) throws Exception {
        String body = """
                {"merchantReference":"MERCH-1","amount":{"amountMinor":10000,"currency":"EGP"},\
                "provider":"SIMULATED_PSP","providerTransactionId":"%s"}""".formatted(externalId);
        HttpResponse<String> response = post("/api/v1/payments", OPERATOR, "pay-" + unique(), body);
        assertThat(response.statusCode()).as("create failed: %s", response.body()).isEqualTo(201);
        return jsonString(response.body(), "id");
    }

    private void authorize(String paymentId) throws Exception {
        assertThat(post("/api/v1/payments/" + paymentId + "/authorize", OPERATOR,
                "auth-" + unique(), "{}").statusCode()).isEqualTo(200);
    }

    private void capture(String paymentId) throws Exception {
        assertThat(post("/api/v1/payments/" + paymentId + "/capture", OPERATOR,
                "cap-" + unique(), "{}").statusCode()).isEqualTo(200);
    }

    private List<SimulatedProviderService.QueuedEvent> queuedEvents(String externalId)
            throws Exception {

        HttpResponse<String> response = get(
                "/api/v1/provider/events?providerTransactionId=" + externalId, OPERATOR);
        String json = response.body();
        List<SimulatedProviderService.QueuedEvent> events = new java.util.ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "\\{[^{}]*?\"eventId\"\\s*:\\s*\"([^\"]*)\"[^{}]*?\"type\"\\s*:\\s*\"([^\"]*)\""
                        + "[^{}]*?\"body\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\")"
                        + "[^{}]*?\"occurredAt\"\\s*:\\s*\"([^\"]*)\""
                        + "[^{}]*?\"timestampOffsetSeconds\"\\s*:\\s*(-?\\d+)"
                        + "[^{}]*?\"signTimestamp\"\\s*:\\s*\"([^\"]*)\""
                        + "[^{}]*?\"signature\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        while (matcher.find()) {
            events.add(new SimulatedProviderService.QueuedEvent(
                    matcher.group(1), matcher.group(2), unescape(matcher.group(3)),
                    Instant.parse(matcher.group(4)), Integer.parseInt(matcher.group(5)),
                    matcher.group(6), matcher.group(7)));
        }
        return events;
    }

    private void deliverEverything(String externalId) throws Exception {
        for (SimulatedProviderService.QueuedEvent event : queuedEvents(externalId)) {
            deliver(event);
        }
    }

    private HttpResponse<String> deliver(SimulatedProviderService.QueuedEvent event) throws Exception {
        return postRaw("/api/v1/provider/webhooks", event.body(), Map.of(
                "X-Provider-Event-Id", event.eventId(),
                "X-Provider-Timestamp", event.signTimestamp(),
                "X-Provider-Signature", event.signature()));
    }

    private String createBatch() throws Exception {
        String body = """
                {"provider":"SIMULATED_PSP","windowStart":"%s","windowEnd":"%s",\
                "subjectMode":"BOTH","async":false}"""
                .formatted(Instant.now().minusSeconds(86_400L), Instant.now().plusSeconds(3600L));
        HttpResponse<String> response = post("/api/v1/reconciliation/batches", ADMIN,
                "batch-" + unique(), body);
        assertThat(response.statusCode())
                .as("starting a batch failed: %s", response.body())
                .isEqualTo(200);
        return jsonString(response.body(), "id");
    }

    private String runBatchInline() throws Exception {
        return createBatch();
    }

    // ------------------------------------------------------------------ HTTP plumbing

    private HttpResponse<String> post(String path, String token, String key, String body)
            throws Exception {

        return postRaw(path, body, Map.of(
                "Authorization", "Bearer " + token,
                "Idempotency-Key", key));
    }

    private HttpResponse<String> postRaw(String path, String body, Map<String, String> headers)
            throws Exception {

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .header("Content-Type", "application/json");
        headers.forEach(request::header);
        request.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    // ------------------------------------------------------------------ readers

    private String stateOf(String paymentId) {
        return sql.sql("SELECT state FROM payments WHERE id = ?").param(paymentId)
                .optional((rs, rowNum) -> rs.getString("state")).orElse(null);
    }

    private List<String> outcomesIn(String batchId) {
        return sql.sql("SELECT outcome FROM reconciliation_results WHERE batch_id = ? "
                + "ORDER BY subject_key").param(batchId)
                .list((rs, rowNum) -> rs.getString("outcome"));
    }

    private Long deltaOf(String externalId) {
        return sql.sql("SELECT delta_minor FROM reconciliation_results WHERE subject_key = ?")
                .param("SIMULATED_PSP:" + externalId)
                .optional((rs, rowNum) -> rs.getObject("delta_minor", Long.class)).orElse(null);
    }

    private int resultCount(String batchId) {
        return sql.sql("SELECT count(*) AS n FROM reconciliation_results WHERE batch_id = ?")
                .param(batchId).optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }

    private int openCaseCount() {
        return sql.sql("SELECT count(*) AS n FROM reconciliation_cases "
                + "WHERE status IN ('OPEN','INVESTIGATING')")
                .optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }

    private String firstCaseId() {
        return sql.sql("SELECT id FROM reconciliation_cases ORDER BY detected_at, id LIMIT 1")
                .optional((rs, rowNum) -> rs.getString("id")).orElse(null);
    }

    private int deliveriesFor(String eventId) {
        return sql.sql("SELECT count(*) AS n FROM provider_event_deliveries WHERE event_id = ?")
                .param(eventId).optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }

    private int eventRows(String eventId) {
        return sql.sql("SELECT count(*) AS n FROM provider_events WHERE event_id = ?")
                .param(eventId).optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }

    private int ledgerTransactionsOfType(String type) {
        return sql.sql("SELECT count(*) AS n FROM ledger_transactions WHERE type = ?")
                .param(type).optional((rs, rowNum) -> rs.getInt("n")).orElse(0);
    }

    private long balanceOf(String accountCode) {
        return sql.sql("""
                SELECT b.balance_minor FROM account_balances b
                  JOIN ledger_accounts a ON a.id = b.account_id
                 WHERE a.code = ? AND b.currency = 'EGP'
                """).param(accountCode).optional((rs, rowNum) -> rs.getLong("balance_minor"))
                .orElse(0L);
    }

    private String statusOf(String sql, String id) {
        return this.sql.sql(sql).param(id)
                .optional((rs, rowNum) -> rs.getString("status")).orElse(null);
    }

    private static String jsonArray(List<String> values) {
        return values.isEmpty()
                ? "[]"
                : values.stream().map(v -> "\"" + v + "\"")
                        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String jsonString(String json, String field) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("no field " + field + " in " + json);
        }
        return matcher.group(1);
    }

    private static String unescape(String quoted) {
        String out = quoted;
        if (out.startsWith("\"") && out.endsWith("\"")) {
            out = out.substring(1, out.length() - 1);
        }
        return out.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String unique() {
        return java.util.UUID.randomUUID().toString();
    }
}
