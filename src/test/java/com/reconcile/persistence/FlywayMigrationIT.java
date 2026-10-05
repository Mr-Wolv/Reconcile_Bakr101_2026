package com.reconcile.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.reconcile.ReconcileApplication;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Migrations as a property of the system rather than as a script that ran once.
 *
 * <p>Maps to rows {@code I-MIG-02} and {@code E2E-MIG-01} of the test matrix. Both are about what
 * happens the <i>second</i> time, which is why neither is covered by the fact that the schema
 * exists: {@code I-MIG-01} proves {@code V1__baseline.sql} applies to an empty database, and this
 * class covers the two cases that a first successful run says nothing about — re-running against a
 * database that is already migrated, and failing part way through an upgrade.
 *
 * <p>The failing case gets its own database inside the shared container rather than its own
 * container, because a migration that half-applied would poison a container every later test in
 * this class depends on. Two databases, one container, no interference.
 */
@Testcontainers
@SpringBootTest
class FlywayMigrationIT {

    /** A deliberately broken pair of migrations; see the files beside this one. */
    private static final String FAILING_LOCATION = "classpath:db/migration-failure";

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.6")
                    .withDatabaseName("reconcile_test")
                    .withUsername("reconcile")
                    .withPassword("reconcile");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("reconcile.security.operator-token", () -> "test-operator-token");
        registry.add("reconcile.security.admin-token", () -> "test-admin-token");
    }

    // ------------------------------------------------------------------ I-MIG-02

    /**
     * Re-running against an already-migrated database changes nothing.
     *
     * <p>The application context above migrated {@code reconcile_test} on its way up, so by the
     * time this runs the database is exactly the "already migrated" case — no setup of its own,
     * and no second container. A second {@code migrate()} must execute zero migrations, leave the
     * history table at one row, and validate clean.
     */
    @Test
    @DisplayName("I-MIG-02: migrating an already-migrated database is a no-op")
    void migratingAnAlreadyMigratedDatabaseIsANoOp() {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();

        MigrateResult result = flyway.migrate();

        assertThat(result.migrationsExecuted)
                .as("the schema is already at the latest version, so there is nothing to do")
                .isZero();
        assertThat(flyway.info().current())
                .as("and a version is still in force afterwards, so the no-op did not leave the "
                        + "database unversioned")
                .isNotNull();
        assertThat(flyway.info().pending())
                .as("with nothing waiting behind it")
                .isEmpty();
        // validate() throws on any problem — a checksum that no longer matches the file, or a pending
        // migration — so "did not throw" is the assertion, rather than inspecting the result
        // object's shape, which is Flyway's to change and not this test's to depend on.
        assertThatCode(() -> flyway.validate())
                .as("no pending changes and no checksum drift")
                .doesNotThrowAnyException();
        // Both versions, once each. The count is a property of how many migrations exist, not of
        // idempotence, so the assertion is on the exact list: a re-run that appended a second row
        // for version 2 would make this ["1","2","2"] and fail, which is the point.
        assertThat(appliedVersions(POSTGRES.getJdbcUrl()))
                .as("one history row per applied version, unchanged: a re-run must not append a "
                        + "second entry for the same version, or the history stops being a record "
                        + "of what ran")
                .containsExactly("1", "2");
    }

    // ------------------------------------------------------------------ E2E-MIG-01

    @Test
    @DisplayName("E2E-MIG-01: a failing migration leaves the schema at the previous version")
    void aFailingMigrationLeavesTheSchemaAtThePreviousVersion() {
        String url = isolatedDatabase("reconcile_mig_failure");
        Flyway flyway = Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(FAILING_LOCATION)
                .load();

        // A lambda rather than a method reference: `migrate()` returns a result, and a method reference
        // cannot be adapted to the void-returning shape catchThrowable takes.
        Throwable thrown = catchThrowable(() -> flyway.migrate());

        assertThat(thrown)
                .as("a migration that cannot run must fail loudly rather than be skipped")
                .isInstanceOf(FlywayException.class);
        assertThat(tableExists(url, "migration_probe"))
                .as("V1 applied, so the schema is at the previous version")
                .isTrue();
        assertThat(tableExists(url, "migration_probe_v2"))
                .as("and nothing from V2 survives. V2 creates this table before it fails, so its "
                        + "absence is what proves the migration was atomic rather than merely "
                        + "stopped at the first bad statement.")
                .isFalse();
        assertThat(appliedVersions(url))
                .as("only the version that succeeded is recorded as applied")
                .containsExactly("1");
    }

    @Test
    @DisplayName("E2E-MIG-01b: the application refuses to start on a schema it cannot migrate")
    void theApplicationRefusesToStartOnAFailingMigration() {
        String url = isolatedDatabase("reconcile_mig_startup");

        Throwable thrown = catchThrowable(() -> new SpringApplicationBuilder(ReconcileApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.datasource.url=" + url,
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword(),
                        "spring.flyway.locations=" + FAILING_LOCATION,
                        // Real-looking secrets, so this cannot pass or fail for the wrong reason:
                        // the startup must break on the migration, not on the dev-token guard.
                        "reconcile.security.operator-token=startup-operator-token",
                        "reconcile.security.admin-token=startup-admin-token",
                        "reconcile.provider.webhook-secret=startup-webhook-secret")
                .run());

        assertThat(thrown)
                .as("an application that cannot reach its schema must not start and serve traffic "
                        + "against whatever happens to be there")
                .isNotNull();
        assertThat(causeChain(thrown))
                .as("and it must fail because of the migration. Asserting only that startup threw "
                        + "would pass on any startup failure at all, including a typo in a property "
                        + "name — which is how a broken deployment passes its own health check.")
                .anyMatch(FlywayException.class::isInstance);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Creates a second database in the same container and returns its JDBC URL.
     *
     * <p>Cheaper than a second container and, more importantly, isolated: a migration that half
     * applied must not be able to poison the database every other test here depends on.
     */
    private static String isolatedDatabase(String name) {
        String adminUrl = POSTGRES.getJdbcUrl();
        try (Connection connection = DriverManager.getConnection(
                adminUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("could not create the isolated database " + name, e);
        }
        return adminUrl.replace("/reconcile_test", "/" + name);
    }

    /** Versions recorded as successfully applied, oldest first. */
    private static List<String> appliedVersions(String url) {
        List<String> versions = new ArrayList<>();
        withConnection(url, statement -> {
            try (ResultSet rows = statement.executeQuery(
                    "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")) {
                while (rows.next()) {
                    versions.add(rows.getString(1));
                }
            }
        });
        return versions;
    }

    private static boolean tableExists(String url, String table) {
        // to_regclass returns NULL rather than raising, which is the difference between asking a
        // question and provoking an error.
        boolean[] found = new boolean[1];
        withConnection(url, statement -> {
            try (ResultSet rows = statement.executeQuery(
                    "SELECT to_regclass('" + table + "') IS NOT NULL")) {
                rows.next();
                found[0] = rows.getBoolean(1);
            }
        });
        return found[0];
    }

    private static void withConnection(String url, SqlStatement action) {
        try (Connection connection = DriverManager.getConnection(
                url, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            action.run(statement);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("statement failed against " + url, e);
        }
    }

    private static List<Throwable> causeChain(Throwable thrown) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = thrown; current != null && !chain.contains(current);
                current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }

    /** A statement to run against a freshly opened connection. */
    private interface SqlStatement {
        void run(Statement statement) throws java.sql.SQLException;
    }
}
