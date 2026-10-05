package com.reconcile.api;

import com.reconcile.service.IdempotencyService;
import com.reconcile.service.PaymentService;
import com.reconcile.service.PayoutService;
import com.reconcile.shared.DomainException;
import com.reconcile.shared.ProblemCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payout endpoints. Admin-only; the route table in {@code SecurityConfig} enforces that.
 *
 * <p>The request body carries <b>no amounts</b> — only which payments to pay. Every figure comes from
 * the ledger. A payout API that accepted an amount would be a withdrawal machine with an audit
 * trail, and the most dangerous property of this endpoint is precisely that it cannot be turned into
 * one.
 */
@RestController
@RequestMapping("/api/v1/payouts")
public class PayoutController {

    private final PayoutService payouts;
    private final IdempotentCommand idempotent;

    public PayoutController(PayoutService payouts, IdempotentCommand idempotent) {
        this.payouts = payouts;
        this.idempotent = idempotent;
    }

    public record ExecutePayoutRequest(
            @NotBlank String merchantReference,
            @NotEmpty List<String> paymentIds) {
    }

    public record PayoutLineResponse(String paymentId, MoneyDto netAmount, int lineNo) {
    }

    public record PayoutResponse(
            String id,
            String merchantReference,
            MoneyDto totalNet,
            int lineCount,
            String status,
            String ledgerTransactionId,
            Instant executedAt,
            List<PayoutLineResponse> lines) {

        static PayoutResponse from(PayoutService.PayoutView view) {
            return new PayoutResponse(
                    view.id(), view.merchantReference(), MoneyDto.of(view.totalNet()),
                    view.lineCount(), view.status(), view.ledgerTransactionId(), view.executedAt(),
                    view.lines().stream()
                            .map(line -> new PayoutLineResponse(
                                    line.paymentId(), MoneyDto.of(line.net()), line.lineNo()))
                            .toList());
        }
    }

    /**
     * Admin-only. A repeated request with the same key returns the original payout instead of
     * paying the merchant twice — the one failure mode here that would be unrecoverable.
     */
    @PostMapping
    public ResponseEntity<String> execute(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ExecutePayoutRequest request,
            HttpServletRequest httpRequest) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new DomainException(
                    ProblemCode.IDEMPOTENCY_KEY_REQUIRED,
                    "POST /api/v1/payouts requires an Idempotency-Key header");
        }

        String requestId = RequestId.current();

        IdempotentCommand.Execution execution = idempotent.execute(
                IdempotencyService.EXECUTE_PAYOUT,
                idempotencyKey,
                IdempotentCommand.rawBody(httpRequest),
                httpRequest.getRequestURI(),
                () -> {
                    PayoutService.PayoutView payout = payouts.execute(
                            request.merchantReference(),
                            request.paymentIds(),
                            PaymentService.CommandContext.of(
                                    new PaymentService.Actor("USER", "api"), requestId,
                                    idempotencyKey));
                    return new IdempotentCommand.Produced(
                            201, PayoutResponse.from(payout), "PAYOUT", payout.id());
                });

        return ResponseEntity.status(execution.status())
                .contentType(execution.contentType())
                .location(URI.create("/api/v1/payouts/" + execution.resourceId()))
                .header("Idempotency-Replayed", Boolean.toString(execution.replayed()))
                .body(execution.json());
    }

    @GetMapping("/{id}")
    public PayoutResponse get(@PathVariable String id) {
        return PayoutResponse.from(payouts.read(id));
    }
}