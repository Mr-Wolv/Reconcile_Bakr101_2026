package com.reconcile.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconcile.provider.WebhookSignature;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.ZoneOffset;
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
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@code I-WEB-08}: what the webhook endpoint does while the database is unreachable.
 *
 * <p>The property is not "it fails". It is that it fails <b>without writing anything</b>, so the
 * provider's retry meets a clean slate and the event is processed exactly once when the database
 * comes back. A handler that recorded a delivery row and then died would make the retry look like
 * a duplicate, which is the one outcome this design exists to avoid.
 *
 * <p><b>How the outage is produced, and why it is not a mock.</b> The application runs against a
 * second database in the container, owned by a non-superuser role, and the test revokes {@code
 * CONNECT} on that database from {@code PUBLIC} and terminates the role's existing sessions. The
 * role cannot open a connection; the test's own connection still can, because the container's
 * {@code reconcile} user is a superuser and superusers bypass the privilege check. That asymmetry
 * is what lets the test inspect the aftermath of an outage it caused.
 *
 * <p>Simulating this by killing the test's own database, or by stubbing the repository, would
 * prove neither: the first would leave the test unable to assert anything, and the second is the
 * mistake the project's own anti-goals section names — a mocked repository proves nothing about
 * transactions, constraints or what is actually durable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebhookWithoutDatabaseIT {

    private static final String SECRET = "webhook-secret-token";
    private static final String APP_ROLE = "reconcile_app";
    private static final String APP_PASSWORD = "app-role-password";

    private static PostgreSQLContainer postgres;
    private static String outageUrl;

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    Clock clock;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = new PostgreSQLContainer("postgres:18.6")
                .withDatabaseName("reconcile_test")
                .withUsername("reconcile")
                .withPassword("reconcile");
        postgres.start();

        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE USER " + APP_ROLE + " WITH PASSWORD '" + APP_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB");
            // Owned by the superuser, deliberately. A database's owner bypasses its access
            // checks entirely, so an application that owned its own database could not be locked
            // out of it by revoking CONNECT — the outage would be unrepresentable and the test
            // would prove nothing. The application gets privileges by grant instead, which is what
            // makes them revocable.
            statement.execute("CREATE DATABASE reconcile_outage");
            statement.execute("GRANT CONNECT, CREATE, TEMPORARY ON DATABASE reconcile_outage TO "
                    + APP_ROLE);
        }
        outageUrl = postgres.getJdbcUrl().replace("/reconcile_test", "/reconcile_outage");

        // PostgreSQL 15 stopped granting CREATE on the public schema to PUBLIC, so Flyway cannot
        // create its history table in a database it does not own without this.
        try (Connection connection = DriverManager.getConnection(
                outageUrl, postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("GRANT ALL ON SCHEMA public TO " + APP_ROLE);
        }
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> outageUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("reconcile.security.operator-token", () -> "operator-secret-token");
        registry.add("reconcile.security.admin-token", () -> "admin-secret-token");
        registry.add("reconcile.provider.webhook-secret", () -> SECRET);
        registry.add("reconcile.provider.event-poll-interval", () -> "1h");
        // Without this the refusal takes the pool's default 30 seconds to arrive, which is a
        // correct behaviour to have in production and a slow one to assert on here.
        registry.add("spring.datasource.hikari.connection-timeout", () -> "2000");
    }

    @BeforeEach
    void restoreConnectivity() {
        // A previous test may have left the database unreachable; the next one starts clean.
        grantConnect();
    }

    // ------------------------------------------------------------------ I-WEB-08

    @Test
    @DisplayName("I-WEB-08: with the database unreachable the webhook is a 5xx and writes nothing")
    void anUnreachableDatabaseIsAServerFaultAndNothingIsPersisted() throws Exception {
        String eventId = "evt-outage-1";
        String body = capturedPayload("psp_outage_1");

        revokeConnectAndTerminateSessions();

        // The precondition, asserted rather than assumed. Without it this test can pass for the
        // wrong reason: if the application's role can still connect, the 202 below would be a
        // normal success dressed up as an outage result.
        assertThatThrownBy(() -> DriverManager.getConnection(outageUrl, APP_ROLE, APP_PASSWORD))
                .as("the application's role must genuinely be unable to connect, or nothing that "
                        + "follows says anything about behaviour during an outage")
                .isInstanceOf(java.sql.SQLException.class);

        HttpResponse<String> duringOutage = send(eventId, body, currentTs());

        grantConnect();
        awaitTheApplicationCanReachTheDatabaseAgain();

        assertThat(duringOutage.statusCode())
                .as("a database that cannot be reached is our fault, not the provider's, so it "
                        + "must not be reported as a client error the provider would stop retrying")
                .isGreaterThanOrEqualTo(500);
        assertThat(duringOutage.body())
                .as("a problem document, and no exception detail in it: the detail sent outward "
                        + "is the request id, because a connection failure is exactly the kind of "
                        + "message that can carry a fragment of the connection string")
                .contains("INTERNAL_ERROR")
                .doesNotContain("Exception");

        assertThat(eventRows(eventId))
                .as("no event row. This is the assertion that matters: if the handler had written "
                        + "part of an event before failing, the provider's retry would be "
                        + "deduplicated against a half-written row and the event would be lost.")
                .isZero();
        assertThat(deliveryRows(eventId))
                .as("no delivery row either, for the same reason — including the rejection row a "
                        + "handler would normally write on every refusal")
                .isZero();

        // Redelivery. The provider retries, the database is back, and this is the moment the whole
        // durable-inbox design exists for.
        HttpResponse<String> afterRecovery = send(eventId, body, currentTs());

        assertThat(afterRecovery.statusCode())
                .as("the same event is accepted once the database returns, which is the only "
                        + "outcome that makes the earlier failure safe")
                .isEqualTo(202);
        assertThat(eventRows(eventId))
                .as("and it is stored exactly once, not twice")
                .isEqualTo(1);
        assertThat(deliveryRows(eventId))
                .as("with only the successful delivery recorded")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Takes the application's database away.
     *
     * <p>Both halves are needed and neither is sufficient. Revoking {@code CONNECT} stops new
     * sessions but leaves the pool's existing connections working, so the handler would sail
     * through and this test would pass for the wrong reason. Terminating sessions without revoking
     * does the same, one connection at a time, as the pool replaces them.
     */
    private void revokeConnectAndTerminateSessions() {
        asSuperuser(statement -> {
            // Both revokes are needed: the explicit grant from setup, and the default one every
            // database carries for PUBLIC.
            statement.execute("REVOKE CONNECT ON DATABASE reconcile_outage FROM " + APP_ROLE);
            statement.execute("REVOKE CONNECT ON DATABASE reconcile_outage FROM PUBLIC");
            statement.execute("""
                    SELECT pg_terminate_backend(pid)
                      FROM pg_stat_activity
                     WHERE datname = 'reconcile_outage' AND pid <> pg_backend_pid()
                    """);
        });
    }

    private void grantConnect() {
        asSuperuser(statement -> {
            statement.execute("GRANT CONNECT ON DATABASE reconcile_outage TO " + APP_ROLE);
            statement.execute("GRANT CONNECT ON DATABASE reconcile_outage TO PUBLIC");
        });
    }

    /**
     * Waits until the application — not the test — can reach the database again.
     *
     * <p>Restoring {@code CONNECT} restores the privilege; it does not restore the pool. The
     * connections that were terminated while it was revoked are still in Hikari's idle set, and
     * Hikari only revalidates a connection it has not used for 500 ms, so the first request after
     * the grant can be handed a corpse and fail. That failure says nothing about the durable inbox,
     * which is what this test is about — it says the pool had not caught up yet.
     *
     * <p>Probing a database-backed endpoint until it answers drains those dead connections, because
     * every borrow that finds one evicts it, and it makes the redelivery below deterministic
     * instead of a race against the pool's housekeeping. It does not relax any assertion: the
     * outage assertions above and the exactly-once assertions below are unchanged. It makes true the
     * precondition the contract states — "redelivery succeeds once the database returns" — which in
     * production is satisfied by the provider's retry backoff rather than by a request issued
     * milliseconds after {@code CONNECT} is restored.
     */
    private void awaitTheApplicationCanReachTheDatabaseAgain() throws Exception {
        HttpRequest probe = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/health"))
                .GET()
                .build();
        for (int attempt = 1; attempt <= 120; attempt++) {
            if (http.send(probe, HttpResponse.BodyHandlers.ofString()).statusCode() == 200) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("the application never reached the database again: /api/v1/health "
                + "did not answer 200 within 30 seconds of CONNECT being restored");
    }

    /** Runs a statement as the container's superuser, which the revoked privilege does not stop. */
    private void asSuperuser(SqlAction action) {
        try (Connection connection = DriverManager.getConnection(
                outageUrl, postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            action.run(statement);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("could not change database connectivity", e);
        }
    }

    private int eventRows(String eventId) {
        return count("SELECT count(*) FROM provider_events WHERE event_id = ?", eventId);
    }

    private int deliveryRows(String eventId) {
        return count("SELECT count(*) FROM provider_event_deliveries WHERE event_id = ?", eventId);
    }

    /**
     * Counts rows with a bound parameter.
     *
     * <p>Two literal statements rather than one built from a table name: string-concatenated SQL is
     * absent from this test suite on purpose, and reintroducing it to save a line would undo a
     * decision the project has already made and explained.
     */
    private int count(String sql, String eventId) {
        try (Connection connection = DriverManager.getConnection(
                outageUrl, postgres.getUsername(), postgres.getPassword());
             java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, eventId);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("could not inspect the outage database", e);
        }
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

    private String capturedPayload(String providerTransactionId) {
        return """
                {"type":"payment.captured","occurredAt":"%s","providerTransactionId":"%s",\
                "merchantReference":"MERCH-1","gross":{"amountMinor":10000,"currency":"EGP"},\
                "net":{"amountMinor":9700,"currency":"EGP"},"status":"CAPTURED"}"""
                .formatted(clock.instant().atOffset(ZoneOffset.UTC).toString(),
                        providerTransactionId);
    }

    private interface SqlAction {
        void run(Statement statement) throws java.sql.SQLException;
    }
}
