package com.reconcile.api;

import com.reconcile.shared.Ulid;

/**
 * The correlation id of the request currently being served.
 *
 * <p>One identifier connects a user's report, the HTTP log line, the audit row and the worker that
 * later touched the same rows. {@code ApiExceptionHandler} quotes it in every problem document, so a
 * client reporting a failure can hand over something an operator can search on.
 *
 * <p>A thread-local rather than a parameter because it has to reach layers that have no business
 * knowing about HTTP — the exception advice, the services that stamp audit rows. {@link
 * RequestIdFilter} is the only thing that sets it, and the only thing that clears it.
 *
 * <p>Outside a request (a scheduled worker, a unit test) this generates a fresh id rather than
 * returning null, so an audit row written by a worker still carries something correlatable.
 */
public final class RequestId {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private RequestId() {
    }

    static void set(String requestId) {
        CURRENT.set(requestId);
    }

    static void clear() {
        CURRENT.remove();
    }

    /** The current request's id, generating a placeholder when there is no request in flight. */
    public static String current() {
        String value = CURRENT.get();
        return value == null ? "req_" + Ulid.next() : value;
    }
}