package com.reconcile.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconcile.shared.DomainException;
import com.reconcile.shared.ProblemCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The provider registry, and the failure it exists to prevent.
 *
 * <p>The property under test is that an unknown provider is <b>refused</b>. A payment stored under
 * a provider no batch will ever filter on is not a payment this system can reconcile, and it is the
 * quietest possible failure: no error, no case, and a payment that simply never appears in a
 * reconciliation run.
 */
class ProvidersTest {

    @Test
    @DisplayName("An absent provider takes the default, because absent means 'I did not care'")
    void absentProviderDefaults() {
        assertThat(Providers.resolve(null)).isEqualTo(Providers.SIMULATED_PSP);
        assertThat(Providers.resolve("")).isEqualTo(Providers.SIMULATED_PSP);
        assertThat(Providers.resolve("   ")).isEqualTo(Providers.SIMULATED_PSP);
    }

    @Test
    @DisplayName("A known provider is kept as the caller wrote it")
    void knownProviderPassesThrough() {
        assertThat(Providers.resolve(Providers.SIMULATED_PSP)).isEqualTo("SIMULATED_PSP");
        assertThat(Providers.isKnown(Providers.SIMULATED_PSP)).isTrue();
    }

    @Test
    @DisplayName("An unknown provider is refused with the documented code, not stored")
    void unknownProviderIsRefused() {
        // A typo is the case that matters: SIMULATD_PSP creates a payment that no reconciliation
        // batch filtered on SIMULATED_PSP will ever see.
        assertThatThrownBy(() -> Providers.resolve("SIMULATD_PSP"))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).code())
                        .isEqualTo(ProblemCode.VALIDATION_FAILED))
                .hasMessageContaining("SIMULATED_PSP");
    }

    @Test
    @DisplayName("There is one definition of the provider, and every main source uses it")
    void theProviderIsDefinedOnce() throws Exception {
        // The regression this whole class exists to prevent: a second literal appearing in a
        // controller or service, one rename away from rows that no batch can find.
        try (var paths = java.nio.file.Files.walk(
                java.nio.file.Path.of("src", "main", "java"))) {

            var offenders = paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.endsWith("Providers.java"))
                    .filter(p -> {
                        try {
                            return java.nio.file.Files.readString(p)
                                    .contains("\"SIMULATED_PSP\"");
                        } catch (java.io.IOException unreadable) {
                            return false;
                        }
                    })
                    .map(java.nio.file.Path::toString)
                    .toList();

            assertThat(offenders)
                    .as("the provider code must come from Providers, not a literal")
                    .isEmpty();
        }
    }
}
