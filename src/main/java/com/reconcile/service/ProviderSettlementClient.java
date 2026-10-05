package com.reconcile.service;

import com.reconcile.config.ReconcileProperties;
import com.reconcile.provider.RemoteSettlement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Pulls settlement reports from the provider (spec 06 §1, decision D5).
 *
 * <p>Exists because "the webhook never arrives" is not an edge case — it is the common failure
 * (F-25), and the only honest answer to it is a pull that runs the same ingestion path as the push.
 * If the two diverged, a settlement would reconcile differently depending on how it arrived, and
 * "it says settled, the webhook says captured" would become undecidable.
 *
 * <p>Two failures are distinguished, because they mean different things operationally and the
 * operator needs to know which one happened:
 *
 * <ul>
 *   <li><b>unreachable or slow</b> → {@link ProviderUnavailableException}, {@code 503 PROVIDER_UNAVAILABLE},
 *       retryable. Nothing was ingested, and retrying the identical window is safe.
 *   <li><b>reachable but the body is nonsense</b> → {@link ProviderMalformedResponseException},
 *       {@code 502}. Retrying immediately will produce the same nonsense; this needs a provider
 *       incident, not a client retry.
 * </ul>
 *
 * <p>Neither is caught and swallowed anywhere above this class. A pull that reports success while
 * having ingested nothing is the one outcome that makes a reconciliation lie.
 *
 * <p>The pull carries {@code reconcile.provider.sync-token} as a bearer credential. Without it the
 * documented default - {@code syncBaseUrl} pointing at this application's own simulator - answers
 * {@code 401}, which this class reports as {@code 503 PROVIDER_UNAVAILABLE}; the caller is told the
 * provider is down when in fact it is refusing us, and retrying changes nothing.
 */
@Component
public class ProviderSettlementClient {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ProviderSettlementClient.class);

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final Duration timeout;
    private final boolean authenticated;

    public ProviderSettlementClient(ReconcileProperties properties, ObjectMapper mapper) {
        this.mapper = mapper;
        this.timeout = properties.provider().syncTimeout();
        this.authenticated = properties.provider().syncTokenConfigured();

        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.provider().syncTimeout());
        factory.setReadTimeout(properties.provider().syncTimeout());

        var builder = RestClient.builder()
                .baseUrl(properties.provider().syncBaseUrl())
                .requestFactory(factory);

        if (authenticated) {
            builder.defaultHeader("Authorization",
                    "Bearer " + properties.provider().syncToken());
        }

        this.restClient = builder.build();
    }

    /**
     * Fetches every settlement the provider reports for a window.
     *
     * @throws ProviderUnavailableException if the provider cannot be reached or does not answer
     * @throws ProviderMalformedResponseException if the answer is not a settlement report
     */
    public List<RemoteSettlement> settlements(Instant windowStart, Instant windowEnd) {
        String body;
        try {
            body = restClient.get()
                    .uri(uri -> uri.path("/settlements")
                            .queryParam("windowStart", windowStart.toString())
                            .queryParam("windowEnd", windowEnd.toString())
                            .build())
                    .retrieve()
                    .body(String.class);
            if (!authenticated) {
                log.debug("pulling from {} without a credential; configure "
                        + "reconcile.provider.sync-token if that provider authenticates reads",
                        "the provider");
            }
        } catch (ResourceAccessException unreachable) {
            throw new ProviderUnavailableException(
                    "the provider did not answer within " + timeout.toSeconds() + "s: "
                            + unreachable.getMessage(), unreachable);
        } catch (RuntimeException failed) {
            throw new ProviderUnavailableException(
                    "the provider call failed: " + failed.getMessage(), failed);
        }

        if (body == null || body.isBlank()) {
            throw new ProviderMalformedResponseException("the provider returned an empty body");
        }

        try {
            RemoteSettlement[] settlements = mapper.readValue(body, RemoteSettlement[].class);
            return settlements == null ? List.of() : List.of(settlements);
        } catch (RuntimeException malformed) {
            throw new ProviderMalformedResponseException(
                    "the provider response is not a settlement report: " + malformed.getMessage());
        }
    }

    /** The provider could not be reached, or did not answer in time. Retryable. */
    public static class ProviderUnavailableException extends com.reconcile.shared.DomainException {

        public ProviderUnavailableException(String detail, Throwable cause) {
            super(com.reconcile.shared.ProblemCode.PROVIDER_UNAVAILABLE, detail, cause);
        }
    }

    /** The provider answered with something that is not a settlement report. Not retryable. */
    public static class ProviderMalformedResponseException extends com.reconcile.shared.DomainException {

        public ProviderMalformedResponseException(String detail) {
            super(com.reconcile.shared.ProblemCode.PROVIDER_BAD_RESPONSE, detail);
        }
    }
}