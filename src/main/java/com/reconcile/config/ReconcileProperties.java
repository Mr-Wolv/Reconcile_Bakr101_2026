package com.reconcile.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything under {@code reconcile.*}, bound from the environment.
 *
 * <p>Secrets arrive here and go nowhere else: they are never persisted, never logged, and never
 * echoed in a problem response. {@link #assertNoDefaultSecretsInProduction()} is the guard that
 * stops a deployment quietly running on the values from {@code application.yml}.
 *
 * <p>Records rather than mutable classes, with every section defaulted by the compact constructor,
 * so that a half-configured bean cannot be observed: every accessor returns a usable value whether
 * or not the environment supplied one. Each nested record declares exactly one (compact)
 * constructor — see that constructor for why an extra overload is not merely inconvenient.
 */
@ConfigurationProperties(prefix = "reconcile")
public record ReconcileProperties(
        Security security,
        Provider provider,
        Idempotency idempotency,
        Reconciliation reconciliation) {

    /**
     * Defaults every section that was not configured.
     *
     * <p>Without this, an absent section is {@code null} and the first caller to read it gets a
     * {@code NullPointerException} at an arbitrary later moment — in this build, the sweeper's
     * constructor. A configuration that omits {@code reconcile.idempotency.*} would have started
     * fine and then failed the first time anything touched that accessor, which is the worst time
     * to find out.
     *
     * <p>Only a <b>compact</b> canonical constructor may be declared here. A second, overloaded
     * constructor would leave Spring Boot's value-object binder with several candidates and no way
     * to choose, and it responds by skipping that section entirely and leaving it null — a failure
     * that produces no error message at all.
     */
    public ReconcileProperties {
        security = security == null ? new Security("", "", List.of()) : security;
        provider = provider == null
                ? new Provider("", List.of(), Duration.ofMinutes(5), "", Duration.ofSeconds(5), "")
                : provider;
        idempotency = idempotency == null
                ? new Idempotency(Duration.ofHours(24), Duration.ofHours(1))
                : idempotency;
        // Each section owns its own defaults, so an absent one is delegated to rather than
        // re-specified here. Spelling the values out in both places is how they drift: the
        // scheduler cadence was written as 5 minutes here and as 2 seconds in the section, and the
        // section's value was silently unreachable. One source of truth per default.
        reconciliation = reconciliation == null
                ? new Reconciliation(null, null)
                : reconciliation;
    }

    /** Bearer-token configuration and the dev-default guard. */
    public record Security(
            String operatorToken,
            String adminToken,
            @DefaultValue List<String> defaultTokens) {

        public Security {
            operatorToken = orEmpty(operatorToken);
            adminToken = orEmpty(adminToken);
            defaultTokens = defaultTokens == null ? List.of() : List.copyOf(defaultTokens);
        }

        private static String orEmpty(String value) {
            return value == null ? "" : value;
        }

        public boolean operatorTokenConfigured() {
            return !operatorToken.isBlank();
        }

        public boolean adminTokenConfigured() {
            return !adminToken.isBlank();
        }
    }

    /**
     * Inbound webhook verification, and the pull path's own settings.
     *
     * <p>{@code syncBaseUrl} points at the provider's settlement API. It defaults to this
     * application's own simulator, because that is what the demos and the end-to-end scenarios run
     * against; a real deployment points it at the PSP and nothing else changes.
     *
     * <p>{@code syncToken} is the credential that pull presents. It is <i>not</i> the operator
     * token by design - a real provider authenticates its settlement API with its own credential -
     * but the default resolves to the operator token so the shipped simulator default works without
     * extra configuration.
     */
    public record Provider(
            String webhookSecret,
            @DefaultValue List<String> defaultWebhookSecrets,
            @DefaultValue Duration signatureWindow,
            @DefaultValue String syncBaseUrl,
            @DefaultValue Duration syncTimeout,
            @DefaultValue String syncToken) {

        public Provider {
            webhookSecret = webhookSecret == null ? "" : webhookSecret;
            defaultWebhookSecrets =
                    defaultWebhookSecrets == null ? List.of() : List.copyOf(defaultWebhookSecrets);
            signatureWindow = signatureWindow == null ? Duration.ofMinutes(5) : signatureWindow;
            syncBaseUrl = syncBaseUrl == null || syncBaseUrl.isBlank()
                    ? "http://127.0.0.1:8080/api/v1/provider"
                    : syncBaseUrl;
            syncTimeout = syncTimeout == null ? Duration.ofSeconds(5) : syncTimeout;
            syncToken = syncToken == null ? "" : syncToken;
        }

        public boolean webhookSecretConfigured() {
            return !webhookSecret.isBlank();
        }

        /**
         * Whether the pull path has a credential to offer.
         *
         * <p>False is a legitimate configuration against a provider that does not authenticate
         * reads, so it is reported rather than treated as a fault. What it must never be is
         * silently wrong: the shipped default for {@code syncBaseUrl} is this application's own
         * simulator, which <i>does</i> authenticate, so an absent token there produces a 401 that
         * surfaces as {@code 503 PROVIDER_UNAVAILABLE} and nothing else.
         */
        public boolean syncTokenConfigured() {
            return !syncToken.isBlank();
        }
    }

    /**
     * How long a stored response is replayed for a repeated idempotency key, and how often the
     * sweeper reclaims expired ones.
     *
     * <p>Two settings because they answer different questions. {@code retention} is a promise to
     * clients — a key replayed within this window always replays. {@code sweepInterval} is an
     * operational choice about when the reclaim happens, and may be shorter or longer without
     * changing any promise.
     */
    public record Idempotency(@DefaultValue Duration retention, @DefaultValue Duration sweepInterval) {
        public Idempotency {
            retention = retention == null ? Duration.ofHours(24) : retention;
            sweepInterval = sweepInterval == null ? Duration.ofHours(1) : sweepInterval;
        }
    }

    /**
     * How soon a created reconciliation batch starts running, and how long a batch may stay
     * {@code RUNNING} before it counts as abandoned.
     *
     * <p>{@code pollInterval} is the scheduler's cadence over batches in {@code RUNNING}. It used
     * to be bound here and read by nothing at all, which is why
     * {@code POST /api/v1/reconciliation/batches} with its documented default ({@code async: true})
     * returned {@code 202} and then nothing happened: the batch sat in {@code RUNNING} with no
     * results until the lease expired and {@code /health} began answering {@code 503}. The default
     * is therefore seconds, not minutes — this is how long an operator waits after asking for a
     * reconciliation, not how often a recurring report runs. There is no recurring report.
     *
     * <p>The lease is an operational claim, not a lock: nothing enforces it, and a batch that is
     * legitimately slower than the lease is reported by {@code /health} as stuck while it is in fact
     * working. That direction of error is chosen deliberately — a false alarm costs one batch a
     * look, a silently abandoned batch costs the business a reconciliation that never happened.
     */
    public record Reconciliation(@DefaultValue Duration pollInterval, @DefaultValue Duration lease) {
        public Reconciliation {
            pollInterval = pollInterval == null ? Duration.ofSeconds(2) : pollInterval;
            lease = lease == null ? Duration.ofMinutes(30) : lease;
        }
    }

    /**
     * Refuses to start when a shipped development secret is still in force outside {@code dev}.
     *
     * <p>A default that reaches a real environment is the single most likely way this system ships
     * with an authentication bypass, and nothing about it is visible at runtime — every request
     * succeeds. Failing to start is loud in a way that a log line is not.
     *
     * @param activeProfile the profile in force
     * @throws IllegalStateException if a configured secret is one of the shipped defaults
     */
    public void assertNoDefaultSecretsInProduction(String activeProfile) {
        boolean relax = "dev".equals(activeProfile) || "test".equals(activeProfile);
        if (relax) {
            return;
        }
        if (security.defaultTokens().contains(security.operatorToken())
                || security.defaultTokens().contains(security.adminToken())) {
            throw new IllegalStateException(
                    "a development API token is still configured outside the dev/test profile; "
                            + "set RECONCILE_OPERATOR_TOKEN and RECONCILE_ADMIN_TOKEN");
        }
        if (provider.defaultWebhookSecrets().contains(provider.webhookSecret())) {
            throw new IllegalStateException(
                    "the development webhook secret is still configured outside the dev/test "
                            + "profile; set RECONCILE_PSP_WEBHOOK_SECRET");
        }
    }
}