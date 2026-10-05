package com.reconcile.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Describes the API for {@code docs/openapi/reconcile-v1.yaml}.
 *
 * <p>Everything here is hand-written because it is the part no generator can infer: the title, the
 * version, and — the one that actually matters — the security scheme. Without it the generated
 * document describes a set of unauthenticated routes, which is both wrong and dangerously
 * reassuring to anyone who reads it.
 *
 * <p>The scheme is declared <b>globally</b> rather than per-operation. Every route except
 * {@code /api/v1/health} and the webhook requires a bearer token; enumerating that per controller
 * would be a second, hand-maintained copy of the rule table in
 * {@link com.reconcile.security.SecurityConfig}, and the two would drift exactly like an
 * undocumented contract would.
 *
 * <p>Deliberately no Swagger UI. The specification is consumed by a drift check and by whoever
 * reads the contract, not by a browser endpoint this service does not otherwise serve.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI reconcileOpenApi() {
        final String schemeName = "bearerAuth";

        return new OpenAPI()
                .info(new Info()
                        .title("Reconcile - payment & settlement reconciliation")
                        .version("v1")
                        .description("""
                                Money movement, provider settlement ingestion, and reconciliation.

                                Every mutating endpoint requires an `Idempotency-Key` and replays a
                                stored response for 24 hours. Refusals are RFC 9457 problem
                                documents; the failure taxonomy is in `docs/spec/07-failure-matrix.md`
                                and the error table in `docs/spec/06-api-contract.md`.

                                Two routes are deliberately unauthenticated: `GET /api/v1/health`,
                                which a load balancer must be able to reach, and
                                `POST /api/v1/provider/webhooks`, which authenticates by HMAC
                                signature rather than by token.
                                """)
                        .license(new License().name("Proprietary")))
                // Pinned to a relative URL rather than left to be inferred from the request. A
                // generated `servers` entry is otherwise the host the check happened to run on,
                // which turns every drift check into a diff of one irrelevant line - and a check
                // that reports noise gets ignored, then disabled.
                .servers(List.of(new Server()
                        .url("/")
                        .description("This application")))
                .components(new Components().addSecuritySchemes(schemeName,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .description("""
                                        A static operator or admin bearer token. Admin implies
                                        operator. Not a production identity design; see the
                                        authentication section of the README for what a real
                                        deployment puts in front of this instead.
                                        """)))
                .addSecurityItem(new SecurityRequirement().addList(schemeName));
    }
}