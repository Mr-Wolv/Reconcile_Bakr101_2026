package com.reconcile.support;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL container for the whole integration-test run.
 *
 * <p>Every integration test class used to declare its own container. That cost roughly 32 seconds
 * per class before a single assertion ran — container start, a full Spring context boot, and a
 * Flyway migration of the baseline schema — which the measured suite put at 632 seconds of startup
 * against 73 seconds of actual test execution. The work was almost entirely duplicated boot work.
 *
 * <p>The container is started <b>lazily</b>, on the first call from a
 * {@code @DynamicPropertySource} supplier, because those suppliers are evaluated while the context
 * is being built: the URL has to exist at the moment it is asked for, and no earlier. That is also
 * what lets a {@code RANDOM_PORT} class use this instead of managing a container by hand — the
 * Testcontainers JUnit extension fails to initialise for those, and starting one in
 * {@code @BeforeAll} per class is the duplication this class exists to remove.
 *
 * <p>The second half of the saving is automatic. Spring caches a context by the values its dynamic
 * properties resolve to, so classes that register the same properties against the same container
 * now share one booted context rather than twenty. Classes that legitimately need different
 * properties — a stub provider, a different database, a short connection timeout — still get their
 * own context, and that is a deliberate difference rather than a regression.
 *
 * <p><b>Why there is a shutdown hook.</b> {@code scripts/verify.sh} sets
 * {@code TESTCONTAINERS_RYUK_DISABLED=true}, and a container started here outlives the class that
 * asked for it. Ryuk would normally reap it when the first client disconnected; here there is no
 * first class, so nothing would stop it and it would sit on the host after every run. The hook
 * stops it when the test JVM exits, which is the boundary at which it is genuinely no longer in
 * use.
 */
public final class SharedPostgres {

    private static PostgreSQLContainer container;

    private SharedPostgres() {
    }

    /** The JDBC URL, starting the container on first use. */
    public static String jdbcUrl() {
        return container().getJdbcUrl();
    }

    public static String username() {
        return container().getUsername();
    }

    public static String password() {
        return container().getPassword();
    }

    private static synchronized PostgreSQLContainer container() {
        if (container != null) {
            return container;
        }
        PostgreSQLContainer started = new PostgreSQLContainer("postgres:18.6")
                .withDatabaseName("reconcile_test")
                .withUsername("reconcile")
                .withPassword("reconcile")
                // Shared by every class, so it must tolerate every class's connections at once.
                //
                // Spring's context cache holds contexts open until the JVM exits, and each one owns
                // a Hikari pool. Nineteen classes share this container, and a dozen of them hold
                // distinct contexts because their properties genuinely differ. At the default pool
                // size that exhausts PostgreSQL's stock 100 connections, and the failure surfaces
                // as a context that will not load — "sorry, too many clients already" — which
                // reads like a configuration bug and is not one.
                //
                // Raising the server's limit is the honest fix. Shrinking each pool would also
                // fit, but it would change what the contention tests exercise: 32 threads in
                // ConcurrencyIT queue on the pool rather than on the rows they are meant to be
                // fighting over, and a test that passes because it never reached the lock it was
                // written to test is worse than a slow one.
                .withCommand("postgres", "-c", "max_connections=500");
        started.start();
        container = started;

        PostgreSQLContainer toStop = started;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                toStop.stop();
            } catch (RuntimeException alreadyGone) {
                // The container is stopped by Ryuk, or by Docker having gone away first. Either
                // way there is nothing left to clean up and nothing useful to report at shutdown.
            }
        }, "shared-postgres-stop"));

        return started;
    }
}
