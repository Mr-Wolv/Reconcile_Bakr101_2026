package com.reconcile.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Minimal RFC 9457 renderer for failures raised <i>inside</i> the security filter chain.
 *
 * <p>{@code @RestControllerAdvice} never sees these: an authentication failure is produced before a
 * handler is chosen, so there is no controller and no advice. Without this, the filter chain's
 * default error page returns HTML, and a JSON client receives a parse error instead of the
 * {@code UNAUTHENTICATED} code it is supposed to branch on.
 *
 * <p>Deliberately minimal. It handles exactly the two cases the filter chain produces and nothing
 * else — a richer problem document belongs to the API layer, where the request id is available.
 */
final class ProblemWriter {

    private ProblemWriter() {
    }

    static void write(HttpServletResponse response, int status, String code, String title, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"type\":\"https://reconcile.local/problems/" + code.toLowerCase(java.util.Locale.ROOT)
                        + "\",\"title\":\"" + title + "\",\"status\":" + status
                        + ",\"code\":\"" + code + "\",\"detail\":\"" + detail + "\"}");
    }
}