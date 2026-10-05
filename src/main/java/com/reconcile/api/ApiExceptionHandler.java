package com.reconcile.api;

import com.reconcile.shared.DomainException;
import com.reconcile.shared.ProblemCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns exceptions into {@code application/problem+json}.
 *
 * <p>Every refusal leaves here in the shape clients are told to expect. Three rules drive the
 * mapping:
 *
 * <ul>
 *   <li><b>A rejected business decision is never a 500.</b> A ledger imbalance is a 422, a duplicate
 *       post a 409. Clients escalate 5xx differently, and a stack trace for a rule working as
 *       designed teaches people to ignore alerts.</li>
 *   <li><b>An illegal transition on an existing resource is 409, not 404.</b> Answering "not found"
 *       when the payment is right there confuses retry logic and leaks nothing useful.</li>
 *   <li><b>Internal messages never reach the client.</b> {@code detail} is written for an operator
 *       reading a log; it carries no stack trace, no SQL, no token and no card number. The cause is
 *       logged with the request id and dropped.</li>
 *   <li><b>Every one of these is {@code application/problem+json}.</b> Not the default
 *       {@code application/json} that returning a POJO would produce: the media type is part of the
 *       contract in {@code docs/spec/06-api-contract.md §3}, and a client that switches on it to tell
 *       a problem document from a success payload would otherwise misread every refusal.</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** The problem document, as committed to {@code docs/spec/06-api-contract.md §3}. */
    public record Problem(
            String type,
            String title,
            int status,
            String code,
            String detail,
            String instance,
            String requestId,
            List<FieldError> errors) {

        public Problem {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        public record FieldError(String field, String message, Object rejectedValue) {
        }
    }

    /**
     * Renders a refusal as the document that will actually be sent.
     *
     * <p>Shared with {@link IdempotentCommand} so a stored 4xx and the response the first caller
     * received are produced by the same code from the same inputs. Serialising the document twice
     * is what would let the two drift, and a replay that differs from the original is exactly the
     * failure this system exists to prevent.
     */
    static Problem problemOf(DomainException e, String instance) {
        return new Problem(
                e.code().typeUri(),
                e.code().title(),
                e.code().httpStatus(),
                e.code().name(),
                e.detail(),
                instance,
                RequestId.current(),
                e.fieldErrors().stream()
                        .map(error -> new Problem.FieldError(
                                error.field(), error.message(), error.rejectedValue()))
                        .toList());
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<Problem> handleDomain(DomainException e, HttpServletRequest request) {
        Problem problem = problemOf(e, request.getRequestURI());

        // 5xx means we broke; 4xx means the caller did something we refuse. Only the former is
        // worth an error-level log line, otherwise a client probing the API fills the log with noise.
        if (e.code().httpStatus() >= 500) {
            log.error("request {} failed with {}", request.getRequestURI(), e.code(), e);
        } else {
            log.debug("request {} refused with {}", request.getRequestURI(), e.code());
        }

        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.code().httpStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (e.code().isRetryable()) {
            // §A3: the caller is told when it may come back, so it does not have to guess a
            // backoff and hammer the endpoint while the winner is still executing. The same applies
            // to a provider that was unavailable and to a ledger breach - both are conditions this
            // system expects to clear, and a client that retries them is behaving correctly.
            response.header("Retry-After", "1");
        }
        return response.body(problem);
    }

    /** Bean Validation on a request body. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Problem> handleInvalidBody(
            MethodArgumentNotValidException e, HttpServletRequest request) {

        List<Problem.FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new Problem.FieldError(
                        fieldError.getField(),
                        fieldError.getDefaultMessage(),
                        fieldError.getRejectedValue()))
                .toList();

        return problem(ProblemCode.VALIDATION_FAILED, "the request body is not valid", request,
                errors);
    }

    /** Unparseable JSON, or a body whose shape does not fit the target type. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Problem> handleUnreadable(
            HttpMessageNotReadableException e, HttpServletRequest request) {

        log.debug("unreadable body on {}: {}", request.getRequestURI(), e.getMessage());
        return problem(ProblemCode.MALFORMED_REQUEST,
                "the request body could not be parsed as JSON matching this endpoint", request);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Problem> handleBadParameter(Exception e, HttpServletRequest request) {
        return problem(ProblemCode.VALIDATION_FAILED,
                "a query parameter is missing or has the wrong type", request);
    }

    /**
     * An unknown path.
     *
     * <p>Handled explicitly because the catch-all below would otherwise swallow it and answer
     * {@code 500}. A caller who mistypes a URL is told the server broke, which is both false and
     * genuinely disruptive: {@code 5xx} triggers alerting and client escalation, while a missing
     * route is an ordinary thing for a client to hit.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Problem> handleNoResource(
            NoResourceFoundException e, HttpServletRequest request) {

        return problem(ProblemCode.RESOURCE_NOT_FOUND,
                "no endpoint " + e.getResourcePath() + " on " + request.getRequestURI(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Problem> handleMethod(
            HttpRequestMethodNotSupportedException e, HttpServletRequest request) {

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new Problem(
                        ProblemCode.METHOD_NOT_ALLOWED.typeUri(),
                        "Method Not Allowed",
                        405,
                        ProblemCode.METHOD_NOT_ALLOWED.name(),
                        e.getMethod() + " is not supported on " + request.getRequestURI(),
                        request.getRequestURI(),
                        RequestId.current(),
                        List.of()));
    }

    /**
     * Anything unexpected.
     *
     * <p>The client is told only the request id; the exception is logged in full against that same
     * id, so an operator can find it. Sending the message outward would risk leaking a fragment of
     * a SQL statement or a connection string.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Problem> handleUnexpected(Exception e, HttpServletRequest request) {
        String requestId = RequestId.current();
        log.error("unhandled exception on {} (requestId={})", request.getRequestURI(), requestId, e);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new Problem(
                ProblemCode.INTERNAL_ERROR.typeUri(),
                ProblemCode.INTERNAL_ERROR.title(),
                500,
                ProblemCode.INTERNAL_ERROR.name(),
                "the request failed unexpectedly; quote this request id when reporting it: "
                        + requestId,
                request.getRequestURI(),
                requestId,
                List.of()));
    }

    private ResponseEntity<Problem> problem(
            ProblemCode code, String detail, HttpServletRequest request) {
        return problem(code, detail, request, List.of());
    }

    private ResponseEntity<Problem> problem(
            ProblemCode code, String detail, HttpServletRequest request,
            List<Problem.FieldError> errors) {

        return ResponseEntity.status(code.httpStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(new Problem(
                        code.typeUri(), code.title(), code.httpStatus(), code.name(), detail,
                        request.getRequestURI(), RequestId.current(), errors));
    }

    /** Exposed so controllers can attach the same correlation id to a success response. */
    public static Map<String, String> correlationHeaders() {
        return Map.of("X-Request-Id", String.valueOf(RequestId.current()));
    }
}