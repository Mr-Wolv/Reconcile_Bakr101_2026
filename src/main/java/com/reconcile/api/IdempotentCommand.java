package com.reconcile.api;

import com.reconcile.service.AuditService;
import com.reconcile.service.IdempotencyService;
import com.reconcile.service.IdempotencyService.Reservation;
import com.reconcile.service.IdempotencyService.Route;
import com.reconcile.shared.DomainException;
import com.reconcile.shared.ProblemCode;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.ContentCachingRequestWrapper;
import tools.jackson.databind.ObjectMapper;

/**
 * The HTTP half of idempotency: reserve, work, record — in one transaction.
 *
 * <p>{@code IdempotencyService} owns the table; this owns the transaction discipline and the wire
 * format. It sits here rather than in the service layer because what it stores is a <i>response</i>,
 * and a response is an HTTP concept.
 *
 * <p>The transaction discipline is the interesting part, and it is §A4 of
 * {@code docs/spec/03-idempotency-and-webhooks.md} taken literally:
 *
 * <ul>
 *   <li><b>Reservation, command and response record share one transaction.</b> A crash therefore
 *       rolls back all three: the key is free again and no side effect exists.
 *       {@code IN_PROGRESS} is only ever visible to a genuinely concurrent request, never left
 *       behind by a dead one — which is precisely what the state is for.</li>
 *   <li><b>A 4xx is stored; a 5xx is not.</b> A business refusal is a deterministic answer, so a
 *       retry must get the same one and must not redo the work. An infrastructure failure is not an
 *       answer at all: caching a 500 turns a transient database blip into a permanently failed
 *       payment. Because the failed transaction rolled the reservation back with the work, the 5xx
 *       case needs no code at all — the key is already free.</li>
 *   <li><b>A business failure is recorded from a second transaction.</b> The command transaction has
 *       to roll back (the side effects are not real), so the 4xx record is written afterwards in a
 *       transaction of its own. That is the only reason this class exists rather than a five-line
 *       try/catch in each controller.</li>
 * </ul>
 *
 * <p>The 4xx body is rendered by {@link ApiExceptionHandler#problemOf}, the same factory the
 * exception advice uses, so the stored bytes and the response the first caller received are
 * identical by construction. A replay that produced a differently-shaped document would break the
 * one guarantee the client actually relies on.
 */
@Service
public class IdempotentCommand {

    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final TransactionTemplate commandTransaction;
    private final TransactionTemplate separateTransaction;

    public IdempotentCommand(
            IdempotencyService idempotency,
            AuditService audit,
            ObjectMapper mapper,
            PlatformTransactionManager transactions) {

        this.idempotency = idempotency;
        this.audit = audit;
        this.mapper = mapper;

        this.commandTransaction = new TransactionTemplate(transactions);
        this.separateTransaction = new TransactionTemplate(transactions);
        this.separateTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * What the command produced.
     *
     * @param resourceId the created resource, stored so a replay can repeat the original
     *                   {@code Location} header rather than inventing a second one
     */
    public record Produced(int status, Object body, String resourceType, String resourceId) {
    }

    /**
     * The answer, in the exact bytes it will be sent.
     *
     * @param json serialised here rather than by the message converter afterwards, because those
     *             bytes are also what gets stored for a replay
     */
    public record Execution(int status, String json, String resourceId, boolean replayed) {

        /**
         * {@code application/problem+json} for a stored refusal, plain JSON for a success.
         *
         * <p>Derived from the status rather than chosen by each controller, because a controller that
         * guessed {@code application/json} would return a problem document under the wrong media
         * type — and the media type is part of the contract clients branch on.
         */
        public MediaType contentType() {
            return status >= 400
                    ? MediaType.APPLICATION_PROBLEM_JSON
                    : MediaType.APPLICATION_JSON;
        }
    }

    /**
     * Runs {@code work} at most once for {@code idemKey} on {@code route}.
     *
     * @param idemKey may be null or blank on endpoints where §A1 makes the key optional; an absent
     *                key is a deliberate choice by the client and is honoured by simply running the
     *                work with no record written
     * @param rawBody the raw request bytes; hashed before deserialisation, per §A2
     */
    public Execution execute(
            Route route,
            String idemKey,
            byte[] rawBody,
            String instanceUri,
            Supplier<Produced> work) {

        if (idemKey == null || idemKey.isBlank()) {
            Produced produced = work.get();
            return new Execution(produced.status(), mapper.writeValueAsString(produced.body()),
                    produced.resourceId(), false);
        }

        String requestId = RequestId.current();
        try {
            return commandTransaction.execute(status -> {
                Reservation reservation = idempotency.reserve(route, idemKey, rawBody, requestId);

                if (reservation instanceof Reservation.Replay replay) {
                    // Written inside this transaction, which commits: a replay is a real event
                    // somebody needs to be able to find, not a debug line.
                    audit.record("USER", "api", "IDEMPOTENCY_KEY_REPLAYED", "IDEMPOTENCY_RECORD",
                            replay.recordId(), requestId,
                            Map.of("endpoint", route.key(), "status", replay.status()));
                    return new Execution(replay.status(), replay.body(), replay.resourceId(), true);
                }

                Reservation.Fresh fresh = (Reservation.Fresh) reservation;
                Produced produced;
                try {
                    produced = work.get();
                } catch (RuntimeException | Error failure) {
                    // Marked rather than inspected at the outer level, because the outer level
                    // cannot otherwise tell a refusal by the *command* from a refusal by the
                    // reservation itself. Both are 409s, and only the first is worth recording: a
                    // fingerprint conflict must leave the stored record untouched, per §A3.
                    throw new WorkFailed(failure);
                }

                String json = mapper.writeValueAsString(produced.body());
                String stored = idempotency.complete(
                        fresh.recordId(), produced.status(), json,
                        produced.resourceType(), produced.resourceId());
                // The bytes the database will replay, not the ones just serialised. jsonb
                // normalises what it stores, so answering with the local copy would make the first
                // caller and every replayed caller see different bytes for one answer.
                return new Execution(produced.status(), stored, produced.resourceId(), false);
            });
        } catch (WorkFailed failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof DomainException domain && isBusinessRefusal(domain)) {
                return rememberRefusal(route, idemKey, rawBody, requestId, domain, instanceUri);
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw failed;
        }
    }

    /**
     * Stores a business refusal and answers the caller with the stored bytes.
     *
     * <p>The command transaction has already rolled back by the time this runs, so the reservation
     * is free again and is taken a second time. Nothing is written if another request got there
     * first: the key is already claimed, and whoever holds it is storing the same refusal.
     *
     * <p>Note the response is returned rather than thrown. The stored document has been through
     * {@code jsonb}, which does not keep a document verbatim, so rendering the first answer a second
     * time from the live exception would produce different bytes from every replay. Returning what
     * was stored is what makes "the retry gets the same answer" literally true.
     */
    private Execution rememberRefusal(
            Route route,
            String idemKey,
            byte[] rawBody,
            String requestId,
            DomainException refusal,
            String instanceUri) {

        int status = refusal.code().httpStatus();
        String document = mapper.writeValueAsString(ApiExceptionHandler.problemOf(refusal, instanceUri));

        try {
            return separateTransaction.execute(txStatus -> {
                Reservation reservation = idempotency.reserve(route, idemKey, rawBody, requestId);
                if (reservation instanceof Reservation.Replay replay) {
                    return new Execution(replay.status(), replay.body(), replay.resourceId(), true);
                }
                Reservation.Fresh fresh = (Reservation.Fresh) reservation;
                String stored = idempotency.complete(fresh.recordId(), status, document, null, null);
                return new Execution(status, stored, null, false);
            });
        } catch (DomainException alreadyClaimed) {
            // A concurrent attempt holds the key and is recording this same refusal. There is
            // nothing for this request to store, so it is answered by the exception advice from
            // the live exception rather than from a record it does not own.
            throw refusal;
        }
    }

    /**
     * The raw request bytes, as {@link RequestIdFilter} buffered them on the way in.
     *
     * <p>Throws rather than falling back to an empty array. A silent fallback would fingerprint
     * every request as identical, which turns the conflict detection into a coin toss: two
     * different bodies would replay each other's stored response instead of being refused.
     */
    public static byte[] rawBody(HttpServletRequest request) {
        BoundedRequestWrapper bounded = findBoundedWrapper(request);
        if (bounded != null) {
            // The webhook's path. The wrapper already refused to read past the limit, so there is
            // nothing to detect here: absent bytes mean the body was over the limit, which is the
            // webhook's own specified answer (413 + a TOO_LARGE delivery row) and not this method's.
            return bounded.body().orElseThrow(() -> new DomainException(
                    ProblemCode.WEBHOOK_TOO_LARGE,
                    "the webhook body exceeds the " + RequestIdFilter.MAX_BODY_BYTES + " byte limit"));
        }

        ContentCachingRequestWrapper cached = findBodyCache(request);
        if (cached == null) {
            throw new IllegalStateException(
                    "the request body cache is missing; RequestIdFilter must wrap every request");
        }

        // The cache is filled as the stream is *read*, so it is empty unless something has already
        // consumed the body. A controller with an @RequestBody parameter gets that for free from
        // message conversion; the webhook endpoint deliberately has no such parameter, because it
        // must verify the bytes before parsing them. Reading through the wrapper is what fills the
        // cache, and reading through the wrapper is what leaves the stream readable downstream.
        if (cached.getContentAsByteArray().length == 0 && request.getContentLengthLong() != 0) {
            try (java.io.InputStream body = cached.getInputStream()) {
                body.readAllBytes();
            } catch (java.io.IOException unreadable) {
                throw new IllegalStateException(
                        "the request body could not be read for verification", unreadable);
            }
        }

        byte[] bytes = cached.getContentAsByteArray();
        long declared = request.getContentLengthLong();
        boolean truncated = declared > bytes.length
                // An unknown length (chunked transfer) means the declared figure cannot be trusted,
                // so a full cache is the only evidence available that the body might not be whole.
                || (declared < 0 && bytes.length >= RequestIdFilter.MAX_BODY_BYTES);
        if (truncated) {
            throw new DomainException(
                    ProblemCode.MALFORMED_REQUEST,
                    "the request body exceeds the " + RequestIdFilter.MAX_BODY_BYTES
                            + " byte limit and cannot be fingerprinted");
        }
        return bytes;
    }

    /**
     * Walks the wrapper chain to find the body cache.
     *
     * <p>The request a controller receives is not the object {@code RequestIdFilter} passed on:
     * Spring Security interposes its own {@code HttpServletRequestWrapper} to hold the security
     * context, so an {@code instanceof} check on the outermost request never matches. Walking the
     * chain is used in preference to reaching for Spring Security's own unwrap helper, because it
     * does not depend on that class being present, staying public, or continuing to wrap the
     * request in the same way in some later version.
     */
    private static BoundedRequestWrapper findBoundedWrapper(HttpServletRequest request) {
        HttpServletRequest current = request;
        while (current != null) {
            if (current instanceof BoundedRequestWrapper bounded) {
                return bounded;
            }
            ServletRequest wrapped = current instanceof HttpServletRequestWrapper wrapper
                    ? wrapper.getRequest()
                    : null;
            current = wrapped instanceof HttpServletRequest narrowed ? narrowed : null;
        }
        return null;
    }

    private static ContentCachingRequestWrapper findBodyCache(HttpServletRequest request) {
        HttpServletRequest current = request;
        while (current != null) {
            if (current instanceof ContentCachingRequestWrapper cached) {
                return cached;
            }
            // getRequest() is declared to return ServletRequest, not HttpServletRequest, so the
            // narrowing has to be checked rather than assumed.
            ServletRequest wrapped = current instanceof HttpServletRequestWrapper wrapper
                    ? wrapper.getRequest()
                    : null;
            current = wrapped instanceof HttpServletRequest narrowed ? narrowed : null;
        }
        return null;
    }

    /** A 4xx is a deterministic answer; a 5xx is a temporary condition and must not be cached. */
    private static boolean isBusinessRefusal(DomainException refusal) {
        int status = refusal.code().httpStatus();
        return status >= 400 && status < 500;
    }

    /** Carries the command's failure out through the transaction so it can be classified outside. */
    private static final class WorkFailed extends RuntimeException {

        WorkFailed(Throwable cause) {
            super(cause);
        }
    }
}