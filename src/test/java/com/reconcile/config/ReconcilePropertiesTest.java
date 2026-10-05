package com.reconcile.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Configuration binding defaults.
 *
 * <p>Written because a section that is absent from the environment used to arrive as {@code null},
 * and the failure surfaced as a {@code NullPointerException} in whichever component happened to read
 * it first — the idempotency retention sweeper, at startup. A configuration record whose stated
 * contract is "no accessor can be null" needs a test, because nothing in the compiler enforces it.
 */
class ReconcilePropertiesTest {

    @Test
    @DisplayName("An entirely unconfigured bean still returns usable values, never null")
    void unconfiguredSectionsFallBackToDefaults() {
        ReconcileProperties properties = new ReconcileProperties(null, null, null, null);

        assertThat(properties.security()).isNotNull();
        assertThat(properties.security().operatorToken()).isEmpty();
        assertThat(properties.security().defaultTokens()).isEmpty();
        assertThat(properties.security().operatorTokenConfigured()).isFalse();

        assertThat(properties.provider()).isNotNull();
        assertThat(properties.provider().webhookSecretConfigured()).isFalse();
        assertThat(properties.provider().signatureWindow()).isEqualTo(Duration.ofMinutes(5));
        // The pull presents no credential unless one is configured. Worth pinning because the
        // shipped default points the pull at this application's own simulator, which does
        // authenticate - so an absent token is invisible here and a 401 there.
        assertThat(properties.provider().syncTokenConfigured()).isFalse();

        assertThat(properties.idempotency()).isNotNull();
        assertThat(properties.idempotency().retention()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.idempotency().sweepInterval()).isEqualTo(Duration.ofHours(1));

        assertThat(properties.reconciliation()).isNotNull();
        // Seconds, not minutes: this is the scheduler's cadence over batches created with
        // `async: true`, so it is how long an operator waits for a reconciliation to start.
        assertThat(properties.reconciliation().pollInterval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.reconciliation().lease()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("A configured value is kept, not overwritten by the default")
    void configuredValuesWin() {
        ReconcileProperties properties = new ReconcileProperties(
                new ReconcileProperties.Security("op-token", "ad-token", java.util.List.of()),
                new ReconcileProperties.Provider("hook", java.util.List.of(), Duration.ofMinutes(9),
                        "http://psp.example/api", Duration.ofSeconds(4), "psp-pull-token"),
                new ReconcileProperties.Idempotency(Duration.ofHours(2), Duration.ofMinutes(30)),
                new ReconcileProperties.Reconciliation(Duration.ofMinutes(11), Duration.ofMinutes(22)));

        assertThat(properties.security().operatorToken()).isEqualTo("op-token");
        assertThat(properties.idempotency().retention()).isEqualTo(Duration.ofHours(2));
        assertThat(properties.idempotency().sweepInterval()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.provider().syncToken()).isEqualTo("psp-pull-token");
        assertThat(properties.provider().syncTokenConfigured()).isTrue();
    }

    @Test
    @DisplayName("Each section declares exactly one constructor, which is what the binder requires")
    void sectionsAreAmbiguityFreeForTheBinder() {
        // Spring Boot's value-object binder skips a section outright when it has several candidate
        // constructors and none is marked - leaving the field null with no error at all. That is
        // not hypothetical: adding an overload to the Idempotency section is exactly what caused
        // a startup NPE in the retention sweeper. Asserting the constructor count keeps the next
        // convenience overload from reintroducing it.
        for (Class<?> section : new Class<?>[] {
                ReconcileProperties.Security.class,
                ReconcileProperties.Provider.class,
                ReconcileProperties.Idempotency.class,
                ReconcileProperties.Reconciliation.class}) {

            assertThat(section.getDeclaredConstructors())
                    .as(section.getSimpleName() + " must have a single candidate constructor")
                    .hasSize(1);
        }
    }
}
