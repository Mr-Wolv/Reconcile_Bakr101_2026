package com.reconcile.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconcile.config.ReconcileProperties;
import com.reconcile.support.SharedPostgres;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Proves that the configured tokens actually resolve to roles.
 *
 * <p>Maps to rows {@code I-SEC-01} and {@code I-SEC-02} in the test matrix.
 *
 * <p>This test exists because its absence was expensive. The token comparison and the property
 * binding both compiled and both looked right, and neither had ever been executed. A container
 * built from the real image then answered every authenticated request with {@code 401} - and the
 * whole suite stayed green, because nothing had ever asked whether a request with a valid token
 * gets in.
 *
 * <p>Scope: this asserts the <i>property and role</i> layer. Whether the deployed filter chain
 * honours these roles is a separate assertion, and it is currently missing - that gap is exactly
 * how the 401 shipped. It needs a request over a real socket (or MockMvc) and is tracked as the
 * first item of the remaining work; adding it here currently breaks the Testcontainers extension's
 * container initialisation, which is a known open problem rather than a passing check.
 */
@SpringBootTest
class SecurityConfigIT {


    @DynamicPropertySource
    static void tokens(DynamicPropertyRegistry registry) {
        // The context is a full one, so it needs a database as well as tokens: Flyway runs before
        // anything else, and an unreachable database fails startup long before security is asked.
        registry.add("spring.datasource.url", SharedPostgres::jdbcUrl);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("reconcile.security.operator-token", () -> "operator-secret-token");
        registry.add("reconcile.security.admin-token", () -> "admin-secret-token");
        registry.add("reconcile.security.default-tokens",
                () -> "dev-operator-token,dev-admin-token");
        registry.add("reconcile.provider.webhook-secret", () -> "webhook-secret-token");
    }

    @Autowired
    SecurityConfig security;

    @Autowired
    ReconcileProperties properties;

    @Test
    @DisplayName("I-SEC-01: the configured operator token binds and resolves to ROLE_OPERATOR")
    void operatorTokenResolves() {
        assertThat(properties.security().operatorToken())
                .as("the operator token must bind from configuration")
                .isEqualTo("operator-secret-token");

        assertThat(security.authoritiesFor("operator-secret-token"))
                .as("a valid operator token must be accepted")
                .isPresent();

        // Asserted against the literal role strings rather than SecurityConfig's constants: a test
        // that reuses the production constant cannot detect the constant being wrong.
        assertThat(security.authoritiesFor("operator-secret-token").orElseThrow())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_OPERATOR");
    }

    @Test
    @DisplayName("I-SEC-02: the admin token implies OPERATOR as well")
    void adminTokenImpliesOperator() {
        assertThat(properties.security().adminToken()).isEqualTo("admin-secret-token");

        assertThat(security.authoritiesFor("admin-secret-token").orElseThrow())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_OPERATOR");
    }

    @Test
    @DisplayName("An unknown or empty token resolves to no authorities")
    void unknownTokenIsRejected() {
        assertThat(security.authoritiesFor("not-a-real-token")).isEmpty();
        assertThat(security.authoritiesFor("")).isEmpty();
        assertThat(security.authoritiesFor(null)).isEmpty();
    }

    @Test
    @DisplayName("An operator token cannot be mistaken for the admin token")
    void rolesDoNotBleedIntoEachOther() {
        assertThat(security.authoritiesFor("operator-secret-token").orElseThrow())
                .as("an operator must not receive the admin role")
                .extracting(GrantedAuthority::getAuthority)
                .doesNotContain("ROLE_ADMIN");
    }

    @Test
    @DisplayName("The webhook secret matches in full, and any difference is a mismatch")
    void webhookSecretMatching() {
        assertThat(security.webhookSecretMatches("webhook-secret-token")).isTrue();
        assertThat(security.webhookSecretMatches("webhook-secret-toke")).isFalse();
        assertThat(security.webhookSecretMatches("webhook-secret-tokenn")).isFalse();
        assertThat(security.webhookSecretMatches(null)).isFalse();
    }

    @Test
    @DisplayName("A shipped development secret is refused outside dev and test")
    void defaultSecretsAreRefusedInProduction() {
        ReconcileProperties withDefaults = new ReconcileProperties(
                new ReconcileProperties.Security(
                        "", "dev-admin-token", List.of("dev-operator-token", "dev-admin-token")),
                new ReconcileProperties.Provider("", List.of(), Duration.ofMinutes(5), "", Duration.ofSeconds(5), ""),
                new ReconcileProperties.Idempotency(Duration.ofHours(24), Duration.ofHours(1)),
                new ReconcileProperties.Reconciliation(Duration.ofMinutes(5), Duration.ofMinutes(30)));

        // dev and test are exempt, because that is where the shipped defaults are meant to work
        withDefaults.assertNoDefaultSecretsInProduction("dev");
        withDefaults.assertNoDefaultSecretsInProduction("test");

        assertThatThrownBy(() -> withDefaults.assertNoDefaultSecretsInProduction("prod"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("development API token");
    }
}
