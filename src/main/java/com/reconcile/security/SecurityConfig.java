package com.reconcile.security;

import com.reconcile.config.ReconcileProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Bearer-token authentication with a two-role model.
 *
 * <p>Stated plainly, because the alternative would be dishonest: <b>this is not a production
 * authentication design.</b> Two static tokens, no user store, no rotation, no OAuth. A real
 * deployment would put OIDC in front and map claims onto the same two roles — and the authorisation
 * layer, the audit actor model and the role matrix are the parts that survive that swap.
 *
 * <p>Tokens are compared by digest rather than by {@code equals}. A byte-by-byte comparison returns
 * at the first differing character, which lets an attacker recover a valid token one character at a
 * time by measuring how long the rejection took. {@link MessageDigest#isEqual} is constant-time in
 * the length it compares, so the rejection cost carries no information about how much of the token
 * was guessed correctly.
 *
 * <p>CSRF is disabled because there is no cookie, no session and no browser client: the credential
 * is a header the attacker cannot cause a victim's browser to attach cross-origin. CORS is disabled
 * for the same reason — there is no browser client to serve.
 */
@Configuration
public class SecurityConfig {

    public static final String ROLE_OPERATOR = "ROLE_OPERATOR";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final ReconcileProperties properties;
    private final Environment environment;

    public SecurityConfig(ReconcileProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // Fails startup outside dev/test if a shipped default secret is still configured. Nothing
        // about that misconfiguration is visible at runtime - every request simply succeeds.
        properties.assertNoDefaultSecretsInProduction(String.join(",", environment.getActiveProfiles()));

        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/api/v1/health").permitAll()
                        // The webhook endpoint authenticates by signature, not by token: the HMAC is
                        // the credential. Excluding it from the filter chain is what makes that true
                        // rather than "token first, signature second".
                        .requestMatchers("/api/v1/provider/webhooks").permitAll()
                        // Admin-only surfaces, from 06-api-contract section 6.
                        .requestMatchers("/api/v1/payments/*/fail").hasRole("ADMIN")
                        .requestMatchers("/api/v1/payouts", "/api/v1/payouts/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/ledger/transactions/*/reverse").hasRole("ADMIN")
                        .requestMatchers("/api/v1/reconciliation/batches").hasRole("ADMIN")
                        .requestMatchers("/api/v1/reconciliation/cases/*/resolve").hasRole("ADMIN")
                        .requestMatchers("/api/v1/reconciliation/cases/*/write-off").hasRole("ADMIN")
                        .requestMatchers("/api/v1/provider/sync").hasRole("ADMIN")
                        // The generated contract is behind the same credential as the API it
                        // describes, deliberately. It is a complete map of every route, every
                        // parameter and every failure code; publishing it unauthenticated would
                        // hand an attacker the inventory and cost nothing to keep it current.
                        // E2E-API-01's drift check reads it with an operator token.
                        // Both shapes are needed: `/v3/api-docs/**` covers sub-paths, and
                        // `/v3/api-docs*` covers the sibling extension forms (`/v3/api-docs.yaml`,
                        // `/v3/api-docs.json`). A bare `/**` matches neither extension - which is
                        // why the JSON document was reachable while the YAML one returned 403.
                        .requestMatchers("/v3/api-docs*", "/v3/api-docs/**").hasRole("OPERATOR")
                        .requestMatchers("/api/v1/**").hasRole("OPERATOR")
                        .anyRequest().denyAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, exception) ->
                                ProblemWriter.write(response, 401, "UNAUTHENTICATED",
                                        "Unauthenticated", "a valid bearer token is required"))
                        .accessDeniedHandler((request, response, exception) ->
                                ProblemWriter.write(response, 403, "FORBIDDEN", "Forbidden",
                                        "this token does not carry the required role")))
                // Inserted immediately before AuthorizationFilter, which is unconditionally present
                // whenever authorizeHttpRequests is used. (The previous anchor,
                // UsernamePasswordAuthenticationFilter, is absent because formLogin is disabled,
                // so ordering against it would be ordering against a filter that never runs.)
                .addFilterBefore(new BearerTokenAuthenticationFilter(this),
                        org.springframework.security.web.access.intercept.AuthorizationFilter.class);

        return http.build();
    }

    /**
     * Resolves a bearer token to its authorities, or empty when the token is not one we know.
     *
     * <p>{@code ADMIN} implies {@code OPERATOR}, so an admin can do everything an operator can
     * without the route table having to enumerate both roles at every entry.
     */
    Optional<List<GrantedAuthority>> authoritiesFor(String presentedToken) {
        ReconcileProperties.Security security = properties.security();

        if (security.adminTokenConfigured() && constantTimeEquals(security.adminToken(), presentedToken)) {
            return Optional.of(List.of(
                    new SimpleGrantedAuthority(ROLE_ADMIN),
                    new SimpleGrantedAuthority(ROLE_OPERATOR)));
        }
        if (security.operatorTokenConfigured()
                && constantTimeEquals(security.operatorToken(), presentedToken)) {
            return Optional.of(List.of(new SimpleGrantedAuthority(ROLE_OPERATOR)));
        }
        return Optional.empty();
    }

    /** Whether {@code presented} is the configured webhook secret. Constant-time. */
    public boolean webhookSecretMatches(String presented) {
        String configured = properties.provider().webhookSecret();
        if (configured.isBlank() || presented == null) {
            return false;
        }
        return constantTimeEquals(configured, presented);
    }

    private static boolean constantTimeEquals(String expected, String presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}