package com.reconcile.api;

import com.reconcile.shared.Ulid;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

/**
 * Establishes the request's correlation id, and caches its raw body.
 *
 * <p>Accepted from the client when present and generated otherwise. Echoed on every response and
 * written onto every audit row.
 *
 * <p>The supplied value is sanitised before it is stored. A client-supplied id ends up in log files
 * and database rows, so a very long one would let a caller fill an index, and one carrying
 * characters a log line can be forged from would let a caller write a misleading entry. Anything
 * outside a conservative character set is dropped rather than rejected — refusing the request over
 * its correlation id would be a worse answer than ignoring it.
 *
 * <p><b>Which layer stops what.</b> The container's header parser is the actual defence against a
 * newline: Tomcat rejects such a request outright, before any application code runs, so the
 * character set below is defence in depth rather than the sole barrier. What it <i>is</i> the
 * barrier for is the characters a well-formed header can carry — spaces, and the printable
 * characters a log viewer would render — and length, which no parser bounds. Both are asserted in
 * {@code ApiHttpIT}.
 *
 * <p>Both the thread-local and the MDC entry are cleared in a {@code finally}. A container reuses
 * threads, and a stale id left behind would be attached to an unrelated later request, which is
 * worse than having no correlation at all because it is actively misleading.
 *
 * <p>Runs first among the application's filters so the id exists before anything that might log.
 *
 * <p><b>Why the body is cached.</b> The idempotency fingerprint is taken over the raw request bytes,
 * before deserialisation ({@code docs/spec/03 §A2}). By the time a controller runs, Spring has
 * already consumed the input stream and it cannot be read again — so the bytes are buffered here on
 * the way through. The cache fills as the stream is read, which is why the wrapper has to be in
 * place before the dispatcher rather than merely before the controller.
 *
 * <p><b>Why oversized bodies are refused here.</b> {@code docs/spec/06 §3} fixes 256 KiB as the
 * limit, answered with {@code MALFORMED_REQUEST}. Checking it at the front door means the request is
 * refused before a transaction opens, rather than after half of it has run. It also keeps the body
 * cache from being truncated: a silently shortened body would fingerprint as something the client
 * never sent, and two different oversized requests could hash identically.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";

    /** {@code docs/spec/06-api-contract.md §3}: the raw body limit. */
    public static final int MAX_BODY_BYTES = 256 * 1024;

    private static final int MAX_LENGTH = 64;

    /**
     * Routes that decide their own size limit.
     *
     * <p>The webhook is the one endpoint whose contract specifies its own refusal: spec 03 §B2
     * requires {@code 413} with a {@code TOO_LARGE} delivery row, which is a different answer from
     * this filter's {@code 400 MALFORMED_REQUEST} — and one that has to be <i>recorded</i>, which
     * means the request has to reach a controller. Refusing it here would produce the right status
     * with no delivery row and no evidence the attempt happened.
     *
     * <p>It also has to be exempt from the {@code Content-Length} check rather than merely
     * prioritised after it, because that header is exactly what a chunked request does not carry.
     */
    private static final String WEBHOOK_PATH = "/api/v1/provider/webhooks";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String supplied = request.getHeader(HEADER);
        String requestId = (supplied == null || supplied.isBlank())
                ? "req_" + Ulid.next()
                : sanitize(supplied);

        RequestId.set(requestId);
        MDC.put("requestId", requestId);
        response.setHeader(HEADER, requestId);
        try {
            if (request.getContentLengthLong() > MAX_BODY_BYTES
                    && !WEBHOOK_PATH.equals(request.getRequestURI())) {
                tooLarge(requestId, request.getContentLengthLong(), response);
                return;
            }
            chain.doFilter(wrapping(request), response);
        } finally {
            RequestId.clear();
            MDC.remove("requestId");
        }
    }

    /**
     * Chooses how a request's body is made readable.
     *
     * <p>The webhook gets a {@link BoundedRequestWrapper} because it is the one endpoint whose
     * length may be undeclared. Every other endpoint refuses an oversize body on
     * {@code Content-Length} before any of it is read, so {@link ContentCachingRequestWrapper} —
     * which caches whatever it is given and only notices a truncation afterwards — is sufficient and
     * is kept, because it is what the idempotency fingerprint path already expects.
     *
     * <p>Before this, both paths were the same wrapper and both were bounded only by declaration:
     * a chunked request has no {@code Content-Length}, so nothing bounded what the webhook read.
     */
    private HttpServletRequest wrapping(HttpServletRequest request) {
        if (WEBHOOK_PATH.equals(request.getRequestURI())) {
            return new BoundedRequestWrapper(request, MAX_BODY_BYTES);
        }
        return new ContentCachingRequestWrapper(request, MAX_BODY_BYTES);
    }

    /**
     * Refuses a body over the documented limit.
     *
     * <p>Written here rather than thrown: no controller has been chosen yet and
     * {@code @RestControllerAdvice} never sees a filter's refusal, which is the same reason
     * {@code ProblemWriter} exists on the security side of the filter chain.
     */
    private static void tooLarge(String requestId, long length, HttpServletResponse response)
            throws IOException {

        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"type\":\"https://reconcile.local/problems/malformed-request\""
                        + ",\"title\":\"Malformed Request\",\"status\":400"
                        + ",\"code\":\"MALFORMED_REQUEST\",\"detail\":\"the request body is "
                        + length + " bytes; the limit is " + MAX_BODY_BYTES + "\""
                        + ",\"instance\":null,\"requestId\":\"" + requestId + "\",\"errors\":[]}");
    }

    /** Keeps only {@code [A-Za-z0-9._-]}, truncated, falling back to a generated id. */
    private static String sanitize(String value) {
        StringBuilder clean = new StringBuilder(Math.min(value.length(), MAX_LENGTH));
        for (int i = 0; i < value.length() && clean.length() < MAX_LENGTH; i++) {
            char c = value.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            if (safe) {
                clean.append(c);
            }
        }
        return clean.isEmpty() ? "req_" + Ulid.next() : clean.toString();
    }
}