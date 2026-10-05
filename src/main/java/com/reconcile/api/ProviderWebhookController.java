package com.reconcile.api;

import com.reconcile.provider.Providers;
import com.reconcile.config.ReconcileProperties;
import com.reconcile.provider.ProviderEventPayload;
import com.reconcile.provider.WebhookSignature;
import com.reconcile.service.ProviderEventService;
import com.reconcile.service.ProviderEventService.Delivery;
import com.reconcile.service.ProviderEventService.DeliveryOutcome;
import com.reconcile.shared.DomainException;
import com.reconcile.shared.ProblemCode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * The provider webhook endpoint: verify, persist, return. Never apply a business effect here.
 *
 * <p>That separation is the design, not a limitation ({@code docs/spec/03 §B4}). If effects ran
 * inside the request, a connection lost mid-processing would leave the provider retrying an
 * operation whose outcome we also do not know — uncertain delivery in both directions, with no way
 * to recover. Persisting first makes the event durable <i>before</i> any effect, so the provider's
 * retry is harmless and our processing is resumable.
 *
 * <p>The verification order below is from §B2 and is not arbitrary: cheapest and most decisive
 * first, so a flood of bad traffic never reaches the database, and freshness is checked before the
 * signature because that bounds replay of a captured request even for an attacker who holds a key.
 */
@RestController
@RequestMapping("/api/v1/provider")
public class ProviderWebhookController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ProviderWebhookController.class);

    /** §B1: the raw body limit. Larger requests are refused before anything else happens. */
    static final int MAX_BODY_BYTES = 256 * 1024;

    private static final String HEADER_EVENT_ID = "X-Provider-Event-Id";
    private static final String HEADER_TIMESTAMP = "X-Provider-Timestamp";
    private static final String HEADER_SIGNATURE = "X-Provider-Signature";

    /**
     * The provider this build talks to.
     *
     * <p>Fixed rather than taken from a header: the provider is a property of the deployment, and
     * accepting it from the request would let a caller with the secret file events under another
     * provider's name.
     */
    private static final String PROVIDER = Providers.SIMULATED_PSP;

    private final ProviderEventService events;
    private final ReconcileProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ProviderWebhookController(
            ProviderEventService events,
            ReconcileProperties properties,
            ObjectMapper mapper,
            Clock clock) {

        this.events = events;
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
    }

    @PostMapping("/webhooks")
    public ResponseEntity<String> receive(
            @RequestHeader(value = HEADER_EVENT_ID, required = false) String eventId,
            @RequestHeader(value = HEADER_TIMESTAMP, required = false) String timestamp,
            @RequestHeader(value = HEADER_SIGNATURE, required = false) String signature,
            HttpServletRequest httpRequest) {

        String requestId = RequestId.current();

        // 1. Size. Checked before parsing, before verifying, and before touching the database: a
        //    10 MB body must not cost a database round trip to be refused.
        //
        //    A DECLARED length over the limit is refused here without reading any of it. An
        //    UNDECLARED one — chunked transfer, which is what F-04 exploited — cannot be checked
        //    at all, so the bound is applied by the wrapper as the body is consumed: it stops after
        //    the limit plus one byte, which is enough to tell "exactly at the limit" from "over it".
        //    The server therefore never takes delivery of more than MAX_BODY_BYTES + 1 bytes of an
        //    undeclared body, no matter what the client actually sends.
        long declaredLength = httpRequest.getContentLengthLong();
        if (declaredLength > MAX_BODY_BYTES) {
            return refuseTooLarge(eventId, requestId,
                    "declared body of " + declaredLength + " bytes exceeds " + MAX_BODY_BYTES);
        }

        // 2. Headers present and well-formed. Ahead of the body read so a request with no event id
        //    is rejected as malformed rather than as un-signable, which is what it is.
        if (eventId == null || eventId.isBlank()) {
            log.warn("rejected webhook: no {} header", HEADER_EVENT_ID);
            events.recordRejection(new Delivery(PROVIDER, "unknown", null,
                    DeliveryOutcome.REJECTED_MALFORMED, 400, false,
                    "missing " + HEADER_EVENT_ID, requestId));
            throw new DomainException(
                    ProblemCode.WEBHOOK_REJECTED_MALFORMED,
                    HEADER_EVENT_ID + " is required");
        }

        byte[] rawBody;
        try {
            rawBody = IdempotentCommand.rawBody(httpRequest);
        } catch (DomainException overLimit) {
            if (overLimit.code() != ProblemCode.WEBHOOK_TOO_LARGE) {
                throw overLimit;
            }
            // Caught rather than allowed to propagate so the refusal is RECORDED. Letting it escape
            // would produce a 413 with no delivery row, which is precisely the F-04 symptom: the
            // documented status without the documented evidence that the attempt happened.
            return refuseTooLarge(eventId, requestId,
                    "the body exceeds " + MAX_BODY_BYTES + " bytes and its length was not declared");
        }

        // 3. Freshness, then 4. signature. See the class comment for why this order.
        //
        //    The event id is inside the signed material, not merely alongside it. It is the key the
        //    inbox deduplicates on, so a signature that did not cover it authenticated the bytes but
        //    not the identity they were filed under — which let a captured valid request be
        //    replayed under a fresh id and enqueued as a second event (F-06).
        Optional<WebhookSignature.Rejection> rejection = WebhookSignature.verify(
                properties.provider().webhookSecret(),
                timestamp,
                eventId,
                signature,
                rawBody,
                clock,
                properties.provider().signatureWindow());

        if (rejection.isPresent()) {
            log.warn("rejected webhook {}: {}", safe(eventId), rejection.get());
            DeliveryOutcome outcome = switch (rejection.get()) {
                case STALE_TIMESTAMP -> DeliveryOutcome.REJECTED_TIMESTAMP;
                case MISSING_TIMESTAMP, MISSING_SIGNATURE, MISSING_EVENT_ID ->
                        DeliveryOutcome.REJECTED_MALFORMED;
                case BAD_SIGNATURE -> DeliveryOutcome.REJECTED_SIGNATURE;
            };
            int status = switch (rejection.get()) {
                case STALE_TIMESTAMP, BAD_SIGNATURE -> 401;
                default -> 400;
            };
            events.recordRejection(new Delivery(PROVIDER, eventId, null, outcome, status, false,
                    rejection.get().name(), requestId));
            throw refusalFor(rejection.get());
        }

        // 5. Parse only now that the bytes are proven genuine. Parsing earlier would mean verifying
        //    a re-serialisation, which §B1 calls out as broken by definition.
        ProviderEventPayload payload;
        try {
            payload = mapper.readValue(rawBody, ProviderEventPayload.class);
        } catch (RuntimeException malformed) {
            // Logged, not returned. A provider that starts sending a shape we do not understand
            // needs a diagnosable reason on our side; the client gets the code, not our parsing
            // vocabulary. The message names fields, never values, so nothing sensitive is logged.
            log.warn("rejected webhook {}: payload is not a valid provider event: {}",
                    eventId, malformed.getMessage());
            events.recordRejection(new Delivery(PROVIDER, eventId, null,
                    DeliveryOutcome.REJECTED_MALFORMED, 400, true,
                    "unparseable payload: " + malformed.getMessage(), requestId));
            throw new DomainException(
                    ProblemCode.WEBHOOK_REJECTED_MALFORMED,
                    "the webhook body is not a valid provider event");
        }

        boolean first = events.accept(
                PROVIDER,
                eventId,
                payload.type(),
                payload.occurredAt(),
                null,
                new String(rawBody, StandardCharsets.UTF_8),
                sha256(rawBody),
                requestId);

        if (!first) {
            // 200, not 202: nothing was accepted, and telling the provider "accepted" would
            // discourage it from ever investigating a genuine delivery problem.
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"duplicate\":true,\"eventId\":\"" + eventId + "\"}");
        }
        return ResponseEntity.status(202)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"accepted\":true,\"eventId\":\"" + eventId + "\"}");
    }

    /**
     * Refuses an oversized body with the status <i>and</i> the evidence the contract promises.
     *
     * <p>The record is the point. An unauthenticated endpoint that is asked to do too much must leave
     * a trace that somebody can read afterwards; returning 413 and forgetting the attempt is how a
     * flood becomes invisible exactly when it is most worth watching.
     */
    private ResponseEntity<String> refuseTooLarge(String eventId, String requestId, String reason) {
        log.warn("rejected webhook {}: {}", safe(eventId), reason);
        events.recordRejection(new Delivery(PROVIDER, safe(eventId), null,
                DeliveryOutcome.TOO_LARGE, 413, false, reason, requestId));
        throw new DomainException(ProblemCode.WEBHOOK_TOO_LARGE,
                "the webhook body exceeds the " + MAX_BODY_BYTES + " byte limit");
    }

    /**
     * The operations backlog: events that exhausted their attempts.
     *
     * <p>Without this, an event that failed permanently would be invisible — it has no business
     * effect and no open case, so nothing else in the system would ever mention it again.
     */
    @GetMapping("/events/failed")
    public FailedEventPage failed() {
        java.util.List<ProviderEventService.FailedEvent> backlog = events.failed(100);
        return new FailedEventPage(backlog.size(), backlog);
    }

    public record FailedEventPage(int count, java.util.List<ProviderEventService.FailedEvent> events) {
    }

    private static DomainException refusalFor(WebhookSignature.Rejection rejection) {
        return switch (rejection) {
            case BAD_SIGNATURE -> new DomainException(
                    ProblemCode.WEBHOOK_REJECTED_SIGNATURE,
                    "the webhook signature does not match; it covers the timestamp, the event id"
                            + " and the body, so any of the three differing invalidates it");
            case STALE_TIMESTAMP -> new DomainException(
                    ProblemCode.WEBHOOK_REJECTED_TIMESTAMP,
                    "the webhook timestamp is outside the accepted window");
            default -> new DomainException(
                    ProblemCode.WEBHOOK_REJECTED_MALFORMED, "the webhook headers are malformed");
        };
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}