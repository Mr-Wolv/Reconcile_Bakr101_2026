package com.reconcile.payment;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The payment lifecycle, as an explicit transition table.
 *
 * <p>This class is the single authority on what a payment may become next. It has no Spring, no
 * persistence and no I/O, so the whole matrix is unit-testable in milliseconds — and a test can
 * enumerate all 36 ordered pairs and assert that each is either permitted or rejected, which means
 * adding a state forces a decision about every edge at the same time.
 *
 * <p>The transitions mirror {@code docs/spec/02-payments.md} T1–T7 exactly. If they disagree, one of
 * them is a bug.
 *
 * <p>What is deliberately <i>not</i> here: {@code CREATED → CAPTURED}, {@code CAPTURED →
 * AUTHORIZED} (there is no un-capture — money is not edited), and any transition out of a terminal
 * state. {@code CREATED → SETTLED} in particular is rejected; settlement money is recognised from a
 * settlement record, never asserted by a caller.
 */
public final class PaymentStateMachine {

    /** What caused a transition. Recorded in state history so an investigation can tell them apart. */
    public enum Trigger {
        /** An operator or client calling the REST API. */
        API,
        /** An inbound, signature-verified provider event. */
        WEBHOOK,
        /** Ingestion of a settlement record covering this payment. */
        SETTLEMENT,
        /** The application itself. */
        SYSTEM
    }

    /** A permitted transition. */
    public record Transition(PaymentState from, PaymentState to, Trigger trigger) { }

    private static final Map<PaymentState, Set<PaymentState>> ALLOWED = Map.of(
            PaymentState.CREATED,    EnumSet.of(PaymentState.AUTHORIZED, PaymentState.FAILED),
            PaymentState.AUTHORIZED, EnumSet.of(PaymentState.CAPTURED, PaymentState.FAILED),
            PaymentState.CAPTURED,   EnumSet.of(PaymentState.SETTLED, PaymentState.REFUNDED),
            PaymentState.SETTLED,    EnumSet.of(PaymentState.REFUNDED),
            PaymentState.FAILED,     EnumSet.noneOf(PaymentState.class),
            PaymentState.REFUNDED,   EnumSet.noneOf(PaymentState.class));

    private PaymentStateMachine() {
    }

    /** Whether {@code from → to} is a permitted transition, for any trigger. */
    public static boolean isAllowed(PaymentState from, PaymentState to) {
        return from != null && to != null && ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * Asserts that {@code from → to} is permitted.
     *
     * @throws IllegalStateTransitionException if the transition is not in the table
     */
    public static void require(PaymentState from, PaymentState to) {
        if (!isAllowed(from, to)) {
            throw new IllegalStateTransitionException(from, to, ALLOWED.getOrDefault(from, Set.of()));
        }
    }

    /** Every state reachable from {@code from}, for error messages and documentation. */
    public static Set<PaymentState> allowedTargets(PaymentState from) {
        return Set.copyOf(ALLOWED.getOrDefault(from, Set.of()));
    }
}