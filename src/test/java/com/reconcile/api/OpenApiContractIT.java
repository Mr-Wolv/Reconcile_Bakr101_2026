package com.reconcile.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconcile.support.SharedPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * E2E-API-01: the committed {@code docs/openapi/reconcile-v1.yaml} matches the running application.
 *
 * <p>The property under test is that <b>the contract cannot drift silently</b>. A hand-written
 * OpenAPI document is a second copy of the API, and the moment it exists nothing forces the two to
 * agree: the document keeps promising an endpoint the code deleted, or the code grows one the
 * document never mentions, and the first person to find out is a client that trusted the document.
 *
 * <p>Compared as a whole document rather than as a set of assertions about individual routes,
 * because a route-level check has to be written by hand against a list somebody maintains - which
 * is the drift it was meant to catch, one level up. Here the list is generated from the code and
 * the diff is the failure.
 *
 * <p>Regeneration is opt-in for the same reason it is on the golden fixture: a check that can
 * rewrite its own expectation is not a check.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiContractIT {

    private static final String OPERATOR = "operator-secret-token";

    /** Where the contract is committed. Referenced by the spec and by the README. */
    private static final Path CONTRACT = Path.of("docs", "openapi", "reconcile-v1.yaml");


    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedPostgres::jdbcUrl);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("reconcile.security.operator-token", () -> OPERATOR);
        registry.add("reconcile.security.admin-token", () -> "admin-secret-token");
        registry.add("reconcile.provider.webhook-secret", () -> "webhook-secret-token");
        registry.add("reconcile.provider.event-poll-interval", () -> "1h");
    }

    @Test
    @DisplayName("E2E-API-01: the committed contract matches the running application")
    void committedContractMatchesTheRunningApplication() throws Exception {
        String generated = fetchContract();

        if (Boolean.getBoolean("reconcile.regenerate-openapi")) {
            try {
                Files.createDirectories(CONTRACT.getParent());
                Files.writeString(CONTRACT, generated, StandardCharsets.UTF_8);
                System.out.println("regenerated " + CONTRACT);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("the contract could not be written", e);
            }
        }

        assertThat(Files.exists(CONTRACT))
                .as("the contract is committed at %s; regenerate with "
                        + "-Dreconcile.regenerate-openapi=true", CONTRACT)
                .isTrue();

        String committed = Files.readString(CONTRACT, StandardCharsets.UTF_8);

        assertThat(normalise(generated))
                .as("""
                        the generated contract differs from %s.

                        Either a route was added, removed or reshaped and the committed document was
                        not updated, or the generator's output changed. Review the difference as a
                        diff - it is the contract changing - then regenerate with:

                            ./scripts/verify.sh -Dreconcile.regenerate-openapi=true \
                                -Dit.test=OpenApiContractIT -Dsurefire.failIfNoSpecifiedTests=false

                        Do not regenerate to make this pass without reading what changed.
                        """, CONTRACT)
                .isEqualTo(normalise(committed));
    }

    @Test
    @DisplayName("The contract is not published to an anonymous caller")
    void contractRequiresACredential() throws Exception {
        HttpRequest anonymous = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v3/api-docs.yaml")).GET().build();

        HttpResponse<String> response = http.send(anonymous, HttpResponse.BodyHandlers.ofString());

        // A complete map of every route, parameter and failure code is inventory. Publishing it
        // unauthenticated would make reconnaissance free and stay current for free too.
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(Files.readString(CONTRACT)).contains("/api/v1/payments");
    }

    private String fetchContract() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/v3/api-docs.yaml"))
                .header("Authorization", "Bearer " + OPERATOR)
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("fetching the contract failed: %s", response.body())
                .isEqualTo(200);
        return response.body();
    }

    /**
     * Line endings only. Trailing whitespace and \r\n are an artefact of the checkout, not of the
     * API, and normalising them keeps the diff about the contract rather than about the platform
     * it was committed from.
     */
    private static String normalise(String yaml) {
        return yaml.replace("\r\n", "\n").strip();
    }

    private String base() {
        return "http://localhost:" + port;
    }
}
