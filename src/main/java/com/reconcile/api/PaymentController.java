package com.reconcile.api;

import com.reconcile.provider.Providers;
import com.reconcile.service.IdempotencyService;
import com.reconcile.service.PaymentService;
import com.reconcile.service.PaymentView;
import com.reconcile.shared.DomainException;
import com.reconcile.shared.Money;
import com.reconcile.shared.ProblemCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payment endpoints, matching {@code docs/spec/06-api-contract.md §1}.
 *
 * <p>Controllers here do three things and no more: bind, delegate, render. Every decision — whether
 * a transition is legal, what the fee is, what the ledger says — belongs to the service or the
 * domain. A controller that decided anything would be a second, untested implementation of the same
 * rule.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService payments;
    private final IdempotentCommand idempotent;

    public PaymentController(PaymentService payments, IdempotentCommand idempotent) {
        this.payments = payments;
        this.idempotent = idempotent;
    }

    // ------------------------------------------------------------------- DTOs

    public record CreatePaymentRequest(
            @NotBlank
            @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$",
                    message = "must be 1-64 characters of A-Z, a-z, 0-9, underscore or hyphen")
            String merchantReference,
            @Size(max = 280) String description,
            @NotNull MoneyDto amount,
            String provider,
            String providerTransactionId) {

        public CreatePaymentRequest {
            if (providerTransactionId != null && providerTransactionId.isBlank()) {
                providerTransactionId = null;
            }
        }
    }

    public record CaptureRequest(MoneyDto expectedAmount) {
    }

    public record FailRequest(@NotBlank @Size(max = 32) String failureCode,
                              @Size(max = 280) String reason) {
    }

    public record PaymentResponse(
            String id,
            String merchantReference,
            String description,
            MoneyDto amount,
            MoneyDto platformFee,
            MoneyDto netAmount,
            String state,
            String failureCode,
            String failureReason,
            String provider,
            String providerTransactionId,
            Instant capturedAt,
            Instant settledAt,
            Instant paidOutAt,
            Instant expiresAt,
            Instant createdAt,
            long version) {

        static PaymentResponse from(PaymentView view) {
            return new PaymentResponse(
                    view.id(), view.merchantReference(), view.description(),
                    MoneyDto.of(view.amount()), MoneyDto.of(view.platformFee()),
                    MoneyDto.of(view.netAmount()), view.state().name(),
                    view.failureCode(), view.failureReason(), view.provider(),
                    view.providerTransactionId(), view.capturedAt(), view.settledAt(),
                    view.paidOutAt(), view.expiresAt(), view.createdAt(), view.version());
        }
    }

    public record PageResponse<T>(List<T> items, String nextCursor) {
    }

    // -------------------------------------------------------------- endpoints

    /**
     * {@code POST /api/v1/payments} → {@code 201 Created}.
     *
     * <p>The {@code Idempotency-Key} header is <b>required</b>, not optional. A retried create that
     * minted a second payment would double-charge a customer, and the only defence is refusing to
     * accept a request that cannot be identified. Requiring it here rather than tolerating its
     * absence means the unsafe path has no way to be reached.
     *
     * <p>The response is the serialised {@link IdempotentCommand.Execution} rather than the record,
     * because those exact bytes are what get stored for a replay. Returning the record and letting
     * the message converter render it later would make the stored copy a second, possibly
     * different, rendering of the same answer.
     */
    @PostMapping
    public ResponseEntity<String> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request,
            HttpServletRequest httpRequest) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new DomainException(
                    ProblemCode.IDEMPOTENCY_KEY_REQUIRED,
                    "POST /api/v1/payments requires an Idempotency-Key header");
        }

        // Resolved here rather than in the record's compact constructor on purpose. An absent
        // provider means the caller did not care and takes the default; a wrong one is a refusal.
        // Doing it here means the refusal arrives as a DomainException and surfaces as
        // 400 VALIDATION_FAILED, whereas throwing from a deserialisation constructor gets wrapped
        // by Jackson and reported as MALFORMED_REQUEST.
        //
        // It used to be inside `if (provider == null || provider.isBlank())`, which meant a
        // non-blank UNKNOWN provider never reached the check at all: the request was accepted 201
        // and persisted under a provider no batch filters on, so it would never reconcile and
        // nothing would ever say so. Exactly the failure Providers' own javadoc describes.
        String provider = Providers.resolve(request.provider());

        Money gross = request.amount().toMoney();
        String requestId = RequestId.current();

        IdempotentCommand.Execution execution = idempotent.execute(
                IdempotencyService.CREATE_PAYMENT,
                idempotencyKey,
                IdempotentCommand.rawBody(httpRequest),
                httpRequest.getRequestURI(),
                () -> {
                    PaymentView created = payments.create(
                            request.merchantReference(),
                            request.description(),
                            gross,
                            provider,
                            request.providerTransactionId(),
                            PaymentService.CommandContext.of(
                                    new PaymentService.Actor("USER", "api"), requestId,
                                    idempotencyKey));
                    return new IdempotentCommand.Produced(
                            201, PaymentResponse.from(created), "PAYMENT", created.id());
                });

        return ResponseEntity.status(execution.status())
                .contentType(execution.contentType())
                .location(URI.create("/api/v1/payments/" + execution.resourceId()))
                .header("Idempotency-Replayed", Boolean.toString(execution.replayed()))
                .body(execution.json());
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable String id) {
        return PaymentResponse.from(payments.read(id));
    }

    @GetMapping
    public PageResponse<PaymentResponse> list(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String merchantReference,
            @RequestParam(required = false) String providerTransactionId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {

        PaymentView.Page page = payments.list(new PaymentService.PaymentQuery(
                state, merchantReference, providerTransactionId, cursor, limit));

        return new PageResponse<>(
                page.items().stream().map(PaymentResponse::from).toList(),
                page.nextCursor());
    }

    @GetMapping("/{id}/timeline")
    public List<TimelineResponse> timeline(@PathVariable String id) {
        return payments.timeline(id).stream()
                .map(entry -> new TimelineResponse(
                        entry.occurredAt(), entry.kind(), entry.reference(), entry.summary(),
                        entry.requestId()))
                .toList();
    }

    /**
     * One line of a payment's merged story.
     *
     * @param kind {@code STATE}, {@code LEDGER}, {@code WEBHOOK} or {@code RECONCILIATION}
     */
    public record TimelineResponse(
            Instant occurredAt,
            String kind,
            String reference,
            String summary,
            String requestId) {
    }

    @PostMapping("/{id}/authorize")
    public ResponseEntity<String> authorize(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {

        return transition(IdempotencyService.AUTHORIZE_PAYMENT, idempotencyKey, httpRequest,
                () -> payments.authorize(id, apiContext(idempotencyKey)));
    }

    @PostMapping("/{id}/capture")
    public ResponseEntity<String> capture(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody(required = false) CaptureRequest request,
            HttpServletRequest httpRequest) {

        Money expected = request == null || request.expectedAmount() == null
                ? null
                : request.expectedAmount().toMoney();

        return transition(IdempotencyService.CAPTURE_PAYMENT, idempotencyKey, httpRequest,
                () -> payments.capture(id, expected, apiContext(idempotencyKey)));
    }

    @PostMapping("/{id}/refund")
    public ResponseEntity<String> refund(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {

        return transition(IdempotencyService.REFUND_PAYMENT, idempotencyKey, httpRequest,
                () -> payments.refund(id, apiContext(idempotencyKey)));
    }

    /** Admin-only, enforced by the route table in {@code SecurityConfig}. */
    @PostMapping("/{id}/fail")
    public ResponseEntity<String> fail(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody FailRequest request,
            HttpServletRequest httpRequest) {

        return transition(IdempotencyService.FAIL_PAYMENT, idempotencyKey, httpRequest,
                () -> payments.fail(id, request.failureCode(), request.reason(),
                        apiContext(idempotencyKey)));
    }

    /**
     * Runs one state transition under the same idempotency rules as the create.
     *
     * <p>The key is optional here (§A1) but honoured when present, because a client retrying a
     * capture after a timeout has exactly the same double-charge problem as one retrying a create —
     * and unlike a create, a repeated capture fails loudly on the second attempt, which is a worse
     * experience than the same answer arriving twice.
     */
    private ResponseEntity<String> transition(
            IdempotencyService.Route route,
            String idempotencyKey,
            HttpServletRequest httpRequest,
            Supplier<PaymentView> work) {

        IdempotentCommand.Execution execution = idempotent.execute(
                route,
                idempotencyKey,
                IdempotentCommand.rawBody(httpRequest),
                httpRequest.getRequestURI(),
                () -> {
                    // Called exactly once. Evaluating the supplier twice would post the ledger twice,
                    // which is precisely the damage this endpoint's key exists to prevent.
                    PaymentView view = work.get();
                    return new IdempotentCommand.Produced(
                            200, PaymentResponse.from(view), "PAYMENT", view.id());
                });

        ResponseEntity.BodyBuilder response = ResponseEntity.status(execution.status())
                .contentType(execution.contentType());
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            response.header("Idempotency-Replayed", Boolean.toString(execution.replayed()));
        }
        return response.body(execution.json());
    }

    /**
     * The actor for a request handled here.
     *
     * <p>{@code USER} with the id {@code api} because this build authenticates with a shared token
     * rather than a user identity. Every audit row therefore attributes the action to the surface
     * that performed it, which is honest: we genuinely do not know which person behind the token did
     * it. A build with real identities would fill this from the authentication principal.
     */
    private PaymentService.CommandContext apiContext(String idempotencyKey) {
        return PaymentService.CommandContext.of(
                new PaymentService.Actor("USER", "api"), RequestId.current(), idempotencyKey);
    }
}