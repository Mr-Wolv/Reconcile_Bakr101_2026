package com.reconcile.shared;

import java.util.List;
import java.util.Objects;

/**
 * A refusal the caller is meant to understand, carrying a stable {@link ProblemCode}.
 *
 * <p>Two things are kept apart on purpose. {@code detail} is written for an operator reading a log
 * and may name an account or a payment; the internal {@code cause} is never rendered to a client,
 * because a PostgreSQL error text can contain a fragment of a query and, in a bad deployment, a
 * connection string.
 *
 * <p>A rejected business decision is <i>not</i> an internal error. "You cannot refund a payment
 * that has already been paid out" is a {@code 409}, not a {@code 500}: clients escalate {@code 5xx}
 * differently, and a stack trace for a rule that is working as designed trains people to ignore
 * alerts.
 */
public class DomainException extends RuntimeException {

    private final ProblemCode code;
    private final transient List<FieldError> fieldErrors;

    public DomainException(ProblemCode code, String detail) {
        this(code, detail, List.of(), null);
    }

    public DomainException(ProblemCode code, String detail, Throwable cause) {
        this(code, detail, List.of(), cause);
    }

    public DomainException(ProblemCode code, String detail, List<FieldError> fieldErrors) {
        this(code, detail, fieldErrors, null);
    }

    public DomainException(
            ProblemCode code, String detail, List<FieldError> fieldErrors, Throwable cause) {
        super(detail, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.fieldErrors = List.copyOf(Objects.requireNonNull(fieldErrors, "fieldErrors"));
    }

    public ProblemCode code() {
        return code;
    }

    public String detail() {
        return getMessage();
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }

    public static DomainException notFound(String what, String id) {
        return new DomainException(
                ProblemCode.RESOURCE_NOT_FOUND, what + " " + id + " does not exist");
    }

    public static DomainException validation(String field, String message, Object rejectedValue) {
        return new DomainException(
                ProblemCode.VALIDATION_FAILED,
                field + ": " + message,
                List.of(new FieldError(field, message, rejectedValue)));
    }

    /**
     * One rejected field. {@code rejectedValue} is echoed back to help the caller fix the request;
     * the API layer never puts secrets or full payloads here.
     */
    public record FieldError(String field, String message, Object rejectedValue) {

        public FieldError {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(message, "message");
        }
    }
}