package com.reconcile.shared;

import java.util.Locale;

/**
 * The stable, machine-readable vocabulary of everything this API can refuse.
 *
 * <p>Clients branch on a {@code code}, never on prose and never on an HTTP status alone. HTTP
 * statuses are too coarse to be useful here — {@code 409} covers four genuinely different business
 * situations — and prose changes whenever someone rewrites a message. A code is a contract.
 *
 * <p>Keeping the status beside the code is deliberate: it makes the mapping
 * {@code docs/spec/06-api-contract.md §3} states impossible to drift, because adding a code forces
 * a decision about the status in the same edit. Every constant here appears in that table.
 */
public enum ProblemCode {

    VALIDATION_FAILED(400),
    IDEMPOTENCY_KEY_REQUIRED(400),
    MALFORMED_REQUEST(400),
    WEBHOOK_REJECTED_MALFORMED(400),
    /** Body over the documented limit. F-21 requires 413, so the code had to exist. */
    WEBHOOK_TOO_LARGE(413),

    WEBHOOK_REJECTED_SIGNATURE(401),
    WEBHOOK_REJECTED_TIMESTAMP(401),
    UNAUTHENTICATED(401),

    FORBIDDEN(403),

    RESOURCE_NOT_FOUND(404),
    METHOD_NOT_ALLOWED(405),

    IDEMPOTENCY_KEY_CONFLICT(409),
    REQUEST_IN_PROGRESS(409),
    PAYMENT_INVALID_STATE(409),
    /**
     * Another payment already carries this provider transaction id. A conflict a caller can cause
     * and fix, so it is a 409 - not the 500 a raw unique-index violation would otherwise produce.
     */
    PROVIDER_TRANSACTION_ALREADY_CLAIMED(409),
    CAPTURE_AMOUNT_MISMATCH(409),
    PAYMENT_ALREADY_PAID_OUT(409),
    PAYOUT_PAYMENT_ALREADY_PAID(409),
    PAYOUT_INSUFFICIENT_CASH(409),
    LEDGER_ALREADY_POSTED(409),
    CASE_INVALID_TRANSITION(409),

    PAYMENT_EXPIRED(410),

    PAYOUT_PAYMENT_NOT_SETTLED(422),
    PAYOUT_MERCHANT_MISMATCH(422),
    PAYOUT_CURRENCY_MISMATCH(422),
    LEDGER_IMBALANCE(422),
    CASE_RESOLUTION_INCOMPLETE(422),

    /** The provider answered, but not with something we can ingest. Not retryable. C-PROV-03. */
    PROVIDER_BAD_RESPONSE(502),

    PROVIDER_UNAVAILABLE(503),
    /** L7 failed: the balance projection disagrees with the entries. The system is not healthy. */
    LEDGER_BALANCE_BREACH(503),

    INTERNAL_ERROR(500);

    private final int httpStatus;

    ProblemCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** Whether a client may usefully retry the identical request. */
    public boolean isRetryable() {
        return this == REQUEST_IN_PROGRESS
                || this == PROVIDER_UNAVAILABLE
                || this == LEDGER_BALANCE_BREACH;
    }

    /** The RFC 9457 {@code type} URI for this code: {@code https://reconcile.local/problems/…}. */
    public String typeUri() {
        return "https://reconcile.local/problems/"
                + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** A short human title, derived from the code so the two can never disagree. */
    public String title() {
        String[] words = name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word, 1, word.length());
        }
        return out.toString();
    }
}