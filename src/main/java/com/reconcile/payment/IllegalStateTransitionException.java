package com.reconcile.payment;

import com.reconcile.shared.DomainException;
import com.reconcile.shared.ProblemCode;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Thrown when a payment is asked to make a transition that is not in the table.
 *
 * <p>A distinct type rather than a bare {@link IllegalStateException} so callers can inspect
 * {@link #from()}, {@link #to()} and {@link #allowed()} without parsing prose — the error contract
 * must never depend on reading an error message.
 *
 * <p><b>It extends {@link DomainException}, and that is load-bearing.</b> The class javadoc used to
 * promise that "the API layer can map it to {@code 409 PAYMENT_INVALID_STATE}", and no such mapping
 * existed: the exception was a bare {@code RuntimeException}, so {@code @RestControllerAdvice}'s
 * catch-all answered {@code 500}. A refusal that is working exactly as designed was being reported
 * as a server fault, which escalates alerts and tells the caller to retry something that will never
 * succeed. Carrying the {@link ProblemCode} on the exception itself is what makes that mapping
 * impossible to forget — there is no longer a type that can escape the contract by accident.
 */
public class IllegalStateTransitionException extends DomainException {

    private final transient PaymentState from;
    private final transient PaymentState to;
    private final transient Set<PaymentState> allowed;

    public IllegalStateTransitionException(
            PaymentState from, PaymentState to, Set<PaymentState> allowed) {

        super(ProblemCode.PAYMENT_INVALID_STATE,
                "Cannot transition payment from " + from + " to " + to
                        + "; allowed from " + from + ": " + allowed.stream()
                        .map(Enum::name).collect(Collectors.toSet()),
                List.of(new FieldError(
                        "state",
                        "expected one of " + allowed.stream().map(Enum::name).sorted().toList(),
                        to.name())));
        this.from = from;
        this.to = to;
        this.allowed = Set.copyOf(allowed);
    }

    public PaymentState from() {
        return from;
    }

    public PaymentState to() {
        return to;
    }

    public Set<PaymentState> allowed() {
        return allowed;
    }
}