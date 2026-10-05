package com.reconcile.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.service.InboundEventWorker;
import com.reconcile.support.SharedPostgres;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
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
 * The simulated provider and the pull path, end to end over real sockets.
 *
 * <p>Maps to {@code C-PROV-01} … {@code C-PROV-04} and the delivery half of the README
 * demonstrations. What these tests hold together is the claim that push and pull are <b>one</b>
 * ingestion path: a settlement delivered by webhook and the same settlement pulled by
 * {@code provider/sync} must produce identical records, identical postings, and identical payment
 * states. The moment they diverge, "the webhook says captured, the pull says settled" becomes
 * undecidable, and a reconciliation report that can be contradicted by a second API is worse than
 * no report.
 *
 * <p>The provider side is a JDK {@link HttpServer}, not a mock, so the real
 * {@code ProviderSettlementClient} is exercised: its timeouts, its status handling and its parsing.
 * A mock would have let the client's error mapping go untested, which is where C-PROV-02 and
 * C-PROV-03 actually live.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProviderSimulatorIT {

    private static final String SECRET = "webhook-secret-token";
    private static final String OPERATOR = "operator-secret-token";
    private static final String ADMIN = "admin-secret-token";
    /** The credential the pull must present. Deliberately different from {@link #OPERATOR}. */
    private static final String PULL_TOKEN = "psp-pull-token";

    /** The stub provider's port. Fixed so it can be handed to the client through properties. */
    private static final int PROVIDER_PORT = 58_101;

    
    private static HttpServer provider;

    /** What the stub provider should answer with. {@code null} means "never answer". */
    private static final AtomicReference<Response> NEXT_RESPONSE = new AtomicReference<>();

    private record Response(int status, String body, boolean hang) {

        static Response json(String body) {
            return new Response(200, body, false);
        }

        static Response neverAnswers() {
            return new Response(200, null, true);
        }
    }

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
    com.reconcile.simulator.SimulatedProviderService simulator;

    @Autowired
    com.reconcile.config.ReconcileProperties properties;

    @Autowired
    tools.jackson.databind.ObjectMapper mapper;

    @BeforeAll
    static void startEverything() throws IOException {
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", PROVIDER_PORT), 0);
        provider.createContext("/settlements", exchange -> {
            // The stub authenticates. It did not before, which is exactly why the pull shipped with
            // no credential at all: nothing in this suite could tell, because the only provider it
            // ever spoke to accepted anonymous reads. Against the shipped default - this
            // application's own simulator, which requires a bearer token - that omission made every
            // `POST /api/v1/provider/sync` answer 401 and surface as 503 PROVIDER_UNAVAILABLE.
            // With the check here, every sync test in the class proves the header is sent.
            String presented = exchange.getRequestHeaders().getFirst("Authorization");
            if (!("Bearer " + PULL_TOKEN).equals(presented)) {
                byte[] denied = "{\"code\":\"unauthenticated\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(401, denied.length);
                exchange.getResponseBody().write(denied);
                exchange.close();
                return;
            }
            Response response = NEXT_RESPONSE.get();
            if (response == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            if (response.hang()) {
                // A provider that never answers. Sleeping is the only faithful way to produce a
                // client-side timeout; returning an error immediately would test the wrong failure.
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
                return;
            }
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        // A cached pool, not the dispatcher thread: one request that deliberately never answers
        // must not stop the stub serving everybody else, or the timeout test would take the rest of
        // the class down with it.
        provider.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        provider.start();
    }

    @AfterAll
    static void stopEverything() {
        if (provider != null) {
            provider.stop(0);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedPostgres::jdbcUrl);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("reconcile.security.operator-token", () -> OPERATOR);
        registry.add("reconcile.security.admin-token", () -> ADMIN);
        registry.add("reconcile.provider.webhook-secret", () -> SECRET);
        registry.add("reconcile.provider.event-poll-interval", () -> "1h");
        registry.add("reconcile.provider.sync-base-url",
                () -> "http://127.0.0.1:" + PROVIDER_PORT);
        registry.add("reconcile.provider.sync-token", () -> PULL_TOKEN);
        // Short, so the timeout row is proved in a second rather than thirty.
        registry.add("reconcile.provider.sync-timeout", () -> "2s");
    }

    @BeforeEach
    void reset() {
        NEXT_RESPONSE.set(null);
        // The simulator is application state, and the database is truncated below. Resetting only
        // one of the two would leave settlements from a previous test queued for the next pull -
        // which is exactly the kind of cross-test state that makes a suite lie.
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

    // ------------------------------------------------------------------ the simulator

    @Test
    @DisplayName("C-PROV-00: a registered payment's queued events are delivered by webhook verbatim")
    void simulatorEventsAreAcceptedVerbatim() throws Exception {
        String externalId = registerPayment("MERCH-1", List.of());
        String paymentId = createCapturedPayment(externalId);

        deliverAll(externalId);
        worker.processBatch(50);

        assertThat(stateOf(paymentId))
                .as("the settlement event the simulator queued settles our captured payment")
                .isEqualTo("SETTLED");
        assertThat(settlementRecordCount()).isEqualTo(1);
        assertThat(providerTransactionRow(externalId))
                .as("the provider's own view of the capture is recorded, which is what "
                        + "reconciliation compares against")
                .isNotNull();
    }

    @Test
    @DisplayName("DUPLICATE_WEBHOOK: the same event id delivered twice is one event and one effect")
    void duplicateWebhookBehaviourIsDeduplicated() throws Exception {
        String externalId = registerPayment("MERCH-1", List.of("DUPLICATE_WEBHOOK"));
        createCapturedPayment(externalId);

        List<QueuedEvent> events = queuedEvents(externalId);
        List<QueuedEvent> captured = events.stream()
                .filter(e -> e.body().contains("payment.captured"))
                .toList();
        assertThat(captured)
                .as("the simulator queues the same event id twice, on purpose")
                .hasSize(2);
        assertThat(captured.get(0).eventId()).isEqualTo(captured.get(1).eventId());

        assertThat(deliver(captured.get(0)).statusCode()).isEqualTo(202);
        assertThat(deliver(captured.get(1)).statusCode()).isEqualTo(200);
        worker.processBatch(50);

        assertThat(sql.sql("SELECT COUNT(*) AS total FROM provider_events WHERE event_id = ?")
                .param(captured.get(0).eventId())
                .optional((rs, rowNum) -> rs.getInt("total"))
                .orElse(-1)).isEqualTo(1);
    }

    @Test
    @DisplayName("DELAYED_WEBHOOK: a stale delivery timestamp is refused with 401")
    void delayedWebhookIsRefused() throws Exception {
        String externalId = registerPayment("MERCH-1", List.of("DELAYED_WEBHOOK"));
        createCapturedPayment(externalId);

        QueuedEvent stale = queuedEvents(externalId).stream()
                .filter(e -> e.body().contains("payment.captured"))
                .findFirst()
                .orElseThrow();

        HttpResponse<String> response = deliver(stale);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("WEBHOOK_REJECTED_TIMESTAMP");
        assertThat(eventCount(stale.eventId()))
                .as("a stale delivery is not a retryable business failure; it is never durable")
                .isZero();
    }

    // ------------------------------------------------------------ restart safety

    @Test
    @DisplayName("A restarted simulator never reissues a provider id that is already bound to a payment")
    void restartedSimulatorDoesNotReissuePersistedIds() throws Exception {
        // A real provider's transaction ids are unique forever. A counter that starts at zero on
        // every process start is not, and the symptom is a 409 against an id the caller had only
        // just been handed - a domain error manufactured by the fixture.
        String first = registerPayment("MERCH-RESTART-A", List.of());
        createCapturedPayment(first);

        // Constructing a second instance against the same database is exactly what a restart does.
        var restarted = new com.reconcile.simulator.SimulatedProviderService(
                properties, mapper, clock, sql);
        String second = restarted.register("MERCH-RESTART-B", 10_000, "EGP", List.of())
                .externalTransactionId();

        assertThat(second).isNotEqualTo(first);
        assertThat(Long.parseLong(second.substring("psp_".length())))
                .as("a restarted simulator must continue past every id already persisted")
                .isGreaterThan(Long.parseLong(first.substring("psp_".length())));

        // And the consequence, which is the part a caller actually sees.
        assertThat(sql.sql("SELECT id FROM payments WHERE provider_transaction_id = ?")
                .param(first)
                .optional((rs, rowNum) -> rs.getString("id")))
                .as("the pre-restart id still belongs to its own payment")
                .isPresent();
        assertThat(sql.sql("SELECT id FROM payments WHERE provider_transaction_id = ?")
                .param(second)
                .optional((rs, rowNum) -> rs.getString("id")))
                .as("and the freshly minted id is claimed by nobody")
                .isEmpty();
    }


    // ------------------------------------------------------------------ C-PROV-01

    @Test
    @DisplayName("C-PROV-01: a settlement covering an unknown transaction is stored, and moves no cash")
    void settlementForUnknownTransactionMovesNoCash() throws Exception {
        long platformCashBefore = balanceOf("PLATFORM_CASH");

        String settlementId = "psset_unknown_" + unique();
        String providerTxn = "psp_never_seen_" + unique();
        String payload = """
                [{"providerSettlementId":"%s","settlementDate":"%s","currency":"EGP",\
                "grossAmountMinor":10000,"feeAmountMinor":300,"netAmountMinor":9700,\
                "lines":[{"providerTransactionId":"%s","grossAmountMinor":10000,\
                "netAmountMinor":9700}]}]"""
                .formatted(settlementId, today(), providerTxn);
        NEXT_RESPONSE.set(Response.json(payload));

        assertThat(sync().statusCode()).isEqualTo(200);

        assertThat(settlementRecordsNamed(settlementId))
                .as("the record is stored: losing it would hide the fact that the provider paid "
                        + "something we have no record of")
                .isEqualTo(1);
        assertThat(balanceOf("PLATFORM_CASH"))
                .as("but no cash is recognised, because nothing of ours was settled")
                .isEqualTo(platformCashBefore);
        assertThat(ledgerTransactionsOfType("SETTLEMENT_RECEIVED"))
                .as("no posting is made for a settlement that settles none of our payments")
                .isZero();
    }

    // ------------------------------------------------------------------ C-PROV-02

    @Test
    @DisplayName("C-PROV-02: a provider that never answers is 503, and ingests nothing")
    void providerTimeoutIsRefusedWithoutPartialIngestion() throws Exception {
        String externalId = registerPayment("MERCH-1", List.of());
        String paymentId = createCapturedPayment(externalId);
        serveSimulatorSettlements();

        NEXT_RESPONSE.set(Response.neverAnswers());

        HttpResponse<String> response = sync();

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("PROVIDER_UNAVAILABLE");
        assertThat(response.headers().firstValue("Retry-After"))
                .as("a retryable code tells the client that retrying the same window is safe")
                .contains("1");
        assertThat(settlementRecordCount())
                .as("nothing was ingested: a partial pull would settle some payments and not "
                        + "others for no stated reason")
                .isZero();
        assertThat(stateOf(paymentId)).isEqualTo("CAPTURED");
    }

    // ------------------------------------------------------------------ C-PROV-03

    @Test
    @DisplayName("C-PROV-03: a malformed provider body is 502, and ingests nothing")
    void malformedProviderResponseIsRefused() throws Exception {
        String externalId = registerPayment("MERCH-1", List.of());
        String paymentId = createCapturedPayment(externalId);
        serveSimulatorSettlements();

        NEXT_RESPONSE.set(Response.json("this is not a settlement report"));

        HttpResponse<String> response = sync();

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).contains("PROVIDER_BAD_RESPONSE");
        assertThat(settlementRecordCount()).isZero();
        assertThat(stateOf(paymentId)).isEqualTo("CAPTURED");
    }

    @Test
    @DisplayName("C-PROV-03b: a provider error status is 503, not a 500 from our own code")
    void providerErrorStatusIsSurfacedAsUnavailable() throws Exception {
        NEXT_RESPONSE.set(new Response(500, "{\"error\":\"down\"}", false));

        HttpResponse<String> response = sync();

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("PROVIDER_UNAVAILABLE");
        assertThat(settlementRecordCount()).isZero();
    }

    // ------------------------------------------------------------------ C-PROV-04

    @Test
    @DisplayName("C-PROV-04: pulling the same settlement twice posts SETTLEMENT_RECEIVED once")
    void repeatedPullIsIdempotent() throws Exception {
        String externalId = registerPayment("MERCH-1", List.of());
        String paymentId = createCapturedPayment(externalId);
        serveSimulatorSettlements();

        assertThat(sync().statusCode()).isEqualTo(200);
        assertThat(sync().statusCode())
                .as("a second pull of the same window is a normal operation, not an error")
                .isEqualTo(200);

        assertThat(settlementRecordCount()).isEqualTo(1);
        assertThat(ledgerTransactionsOfType("SETTLEMENT_RECEIVED"))
                .as("the posting is the thing that would move real money twice")
                .isEqualTo(1);
        assertThat(stateOf(paymentId)).isEqualTo("SETTLED");
        assertThat(ledgerImbalanceCount()).isZero();
    }

    @Test
    @DisplayName("A pulled settlement produces exactly what the same webhook would have")
    void pullAndPushAgree() throws Exception {
        String externalId = registerPayment("MERCH-1", List.of());
        createCapturedPayment(externalId);
        serveSimulatorSettlements();
        assertThat(sync().statusCode()).isEqualTo(200);

        long entriesAfterPull = ledgerEntryCount();
        long cashAfterPull = balanceOf("PLATFORM_CASH");

        assertThat(cashAfterPull)
                .as("the pull moved the settlement into PLATFORM_CASH at the gross: the fee was "
                        + "recognised as revenue at capture, so it is not a cash movement here")
                .isEqualTo(10_000L);

        // Re-delivering the same settlement as a webhook must change nothing at all.
        QueuedEvent settlementEvent = queuedEvents(externalId).stream()
                .filter(e -> e.type().equals("settlement.paid"))
                .findFirst()
                .orElseThrow();
        assertThat(deliver(settlementEvent).statusCode())
                .as("a push that arrives after the pull has already ingested it")
                .isIn(200, 202);
        worker.processBatch(50);

        assertThat(ledgerEntryCount()).isEqualTo(entriesAfterPull);
        assertThat(balanceOf("PLATFORM_CASH")).isEqualTo(cashAfterPull);
        assertThat(settlementRecordCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private void serveSimulatorSettlements() throws Exception {
        HttpResponse<String> response = get("/api/v1/provider/settlements", OPERATOR);
        assertThat(response.statusCode()).isEqualTo(200);
        NEXT_RESPONSE.set(Response.json(response.body()));
    }

    private String registerPayment(String merchantReference, List<String> behaviours)
            throws Exception {

        String body = """
                {"merchantReference":"%s","grossAmountMinor":10000,"currency":"EGP",\
                "behaviour":%s}"""
                .formatted(merchantReference, jsonArray(behaviours));

        HttpResponse<String> response = post("/api/v1/provider/payments", OPERATOR, body);
        assertThat(response.statusCode())
                .as("registering with the simulator failed: %s", response.body())
                .isEqualTo(201);
        return jsonString(response.body(), "externalTransactionId");
    }

    private List<QueuedEvent> queuedEvents(String externalId) throws Exception {
        HttpResponse<String> response =
                get("/api/v1/provider/events?providerTransactionId=" + externalId, OPERATOR);
        assertThat(response.statusCode()).isEqualTo(200);
        String json = response.body();
        List<QueuedEvent> events = new java.util.ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "\\{[^{}]*?\"eventId\"\\s*:\\s*\"([^\"]*)\"[^{}]*?\"type\"\\s*:\\s*\"([^\"]*)\""
                        + "[^{}]*?\"body\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\")"
                        + "[^{}]*?\"occurredAt\"\\s*:\\s*\"([^\"]*)\""
                        + "[^{}]*?\"timestampOffsetSeconds\"\\s*:\\s*(-?\\d+)"
                        + "[^{}]*?\"signTimestamp\"\\s*:\\s*\"([^\"]*)\""
                        + "[^{}]*?\"signature\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        while (matcher.find()) {
            events.add(new QueuedEvent(
                    matcher.group(1),
                    matcher.group(2),
                    unescape(matcher.group(3)),
                    matcher.group(4),
                    Integer.parseInt(matcher.group(5)),
                    matcher.group(6),
                    matcher.group(7)));
        }
        return events;
    }

    private record QueuedEvent(
            String eventId, String type, String body, String occurredAt,
            int timestampOffsetSeconds, String signTimestamp, String signature) {
    }

    private void deliverAll(String externalId) throws Exception {
        for (QueuedEvent event : queuedEvents(externalId)) {
            deliver(event);
        }
    }

    private HttpResponse<String> deliver(QueuedEvent event) throws Exception {
        return post("/api/v1/provider/webhooks", null, event.body(), Map.of(
                "X-Provider-Event-Id", event.eventId(),
                "X-Provider-Timestamp", event.signTimestamp(),
                "X-Provider-Signature", event.signature()));
    }

    private HttpResponse<String> sync() throws Exception {
        String window = """
                {"windowStart":"%s","windowEnd":"%s"}"""
                .formatted(Instant.now().minusSeconds(86_400), Instant.now().plusSeconds(86_400));
        return post("/api/v1/provider/sync", ADMIN, window);
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        return post(path, token, body, Map.of());
    }

    private HttpResponse<String> post(
            String path, String token, String body, Map<String, String> headers) throws Exception {

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .header("Content-Type", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        headers.forEach(request::header);
        request.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private String createCapturedPayment(String providerTransactionId) throws Exception {
        String body = """
                {"merchantReference":"MERCH-1","amount":{"amountMinor":10000,"currency":"EGP"},\
                "provider":"SIMULATED_PSP","providerTransactionId":"%s"}"""
                .formatted(providerTransactionId);
        String paymentId = jsonString(apiCall("/api/v1/payments", body), "id");
        apiCall("/api/v1/payments/" + paymentId + "/authorize", "{}");
        apiCall("/api/v1/payments/" + paymentId + "/capture", "{}");
        return paymentId;
    }

    private String apiCall(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base() + path))
                .header("Authorization", "Bearer " + OPERATOR)
                .header("Idempotency-Key", "sim-" + unique())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IllegalStateException(path + " -> " + response.statusCode() + " " + response.body());
        }
        return response.body();
    }

    // ------------------------------------------------------------------ database readers

    private String stateOf(String paymentId) {
        return sql.sql("SELECT state FROM payments WHERE id = ?").param(paymentId)
                .optional((rs, rowNum) -> rs.getString("state")).orElse(null);
    }

    private String providerTransactionRow(String externalTransactionId) {
        return sql.sql("SELECT id FROM provider_transactions WHERE provider_transaction_id = ?")
                .param(externalTransactionId)
                .optional((rs, rowNum) -> rs.getString("id")).orElse(null);
    }

    private int eventCount(String eventId) {
        return sql.sql("SELECT COUNT(*) AS total FROM provider_events WHERE event_id = ?")
                .param(eventId)
                .optional((rs, rowNum) -> rs.getInt("total")).orElse(-1);
    }

    private int settlementRecordCount() {
        return sql.sql("SELECT COUNT(*) AS total FROM settlement_records")
                .optional((rs, rowNum) -> rs.getInt("total")).orElse(-1);
    }

    private int settlementRecordsNamed(String providerSettlementId) {
        return sql.sql("SELECT COUNT(*) AS total FROM settlement_records WHERE provider_settlement_id = ?")
                .param(providerSettlementId)
                .optional((rs, rowNum) -> rs.getInt("total")).orElse(-1);
    }

    private int ledgerTransactionsOfType(String type) {
        return sql.sql("SELECT COUNT(*) AS total FROM ledger_transactions WHERE type = ?")
                .param(type)
                .optional((rs, rowNum) -> rs.getInt("total")).orElse(-1);
    }

    private int ledgerEntryCount() {
        return sql.sql("SELECT COUNT(*) AS total FROM ledger_entries")
                .optional((rs, rowNum) -> rs.getInt("total")).orElse(-1);
    }

    private int ledgerImbalanceCount() {
        return sql.sql("SELECT COUNT(*) AS total FROM ledger_transactions t "
                + "WHERE ABS((SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount_minor "
                + "ELSE -amount_minor END), 0) FROM ledger_entries WHERE transaction_id = t.id)) > 0")
                .optional((rs, rowNum) -> rs.getInt("total")).orElse(-1);
    }

    private long balanceOf(String accountCode) {
        return sql.sql("""
                SELECT b.balance_minor FROM account_balances b
                  JOIN ledger_accounts a ON a.id = b.account_id
                 WHERE a.code = ? AND b.currency = 'EGP'
                """).param(accountCode)
                .optional((rs, rowNum) -> rs.getLong("balance_minor")).orElse(0L);
    }

    private String today() {
        return java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
    }

    private static String jsonArray(List<String> values) {
        if (values.isEmpty()) {
            return "[]";
        }
        return values.stream().map(v -> "\"" + v + "\"").collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String jsonString(String json, String field) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("no field " + field + " in " + json);
        }
        return matcher.group(1);
    }

    /** The simulator returns JSON-escaped bodies; this puts the provider's bytes back. */
    private static String unescape(String quotedJsonString) {
        String out = quotedJsonString;
        if (out.startsWith("\"") && out.endsWith("\"")) {
            out = out.substring(1, out.length() - 1);
        }
        return out.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String unique() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}
