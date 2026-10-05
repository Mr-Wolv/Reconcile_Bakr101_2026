package com.reconcile.shared;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * An opaque keyset cursor: base64url of {@code instant|id}.
 *
 * <p><b>Why keyset and not offset.</b> Offset pagination counts rows to skip, so page 500 of a
 * growing table is slower than page 1, and a row inserted between two requests shifts every later
 * page by one — the client silently sees a duplicate and silently misses an entry. On a financial
 * audit query that is not a performance nit, it is a wrong answer. A cursor names a position, so
 * the next page starts exactly where the last one stopped regardless of what arrived in between.
 *
 * <p><b>Why opaque.</b> A client must not be able to construct one. If the cursor were a readable
 * pair of values, a caller could hand-edit the instant to skip an arbitrary stretch of the ledger,
 * quietly missing entries it was meant to see, and the endpoint would report success while lying.
 * Base64 is not encryption — it is obscurity — and that is exactly the point: the format is not
 * part of the contract, so a client that reads it is relying on something we may change.
 *
 * <p><b>Why the decode fails loudly.</b> A malformed cursor is a client mistake and becomes a clean
 * {@code 400 VALIDATION_FAILED}, never a parse exception and never a silently-ignored parameter. An
 * endpoint that quietly discards a cursor it does not understand is worse than one that refuses it:
 * it returns the first page while the caller believes it asked for the hundredth.
 *
 * <p>Lives in {@code shared} because two modules paginate — payments and the statement of account —
 * and one definition of "opaque cursor" is the only way the two cannot drift apart.
 */
public record PageCursor(Instant instant, String id) {

    public PageCursor {
        if (instant == null) {
            throw new IllegalArgumentException("cursor instant is required");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("cursor id is required");
        }
    }

    /** Renders the cursor a client passes back on the next request. */
    public static String encode(Instant instant, String id) {
        String raw = instant + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Parses a cursor, or returns {@code null} for an absent one.
     *
     * @throws DomainException {@code VALIDATION_FAILED} if the cursor is present but unreadable
     */
    public static PageCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.indexOf('|');
            if (separator < 0) {
                throw new IllegalArgumentException("missing separator");
            }
            return new PageCursor(Instant.parse(raw.substring(0, separator)),
                    raw.substring(separator + 1));
        } catch (RuntimeException e) {
            throw DomainException.validation(
                    "cursor", "is not a valid pagination cursor", null);
        }
    }
}
