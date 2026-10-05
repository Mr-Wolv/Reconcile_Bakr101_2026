package com.reconcile.service;

import com.reconcile.config.ReconcileProperties;
import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.shared.DomainException;
import com.reconcile.shared.ProblemCode;
import com.reconcile.shared.Ulid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Makes a retried request safe to repeat, per {@code docs/spec/03-idempotency-and-webhooks.md §A}.
 *
 * <p>This class owns the <b>table</b> and nothing else. It does not decide whether a request is
 * business or infrastructure, it does not serialise anything, and it does not know about HTTP. The
 * transaction discipline around it lives in {@code com.reconcile.api.IdempotentCommand}, because
 * that is where the protocol is.
 *
 * <p>Four outcomes, and the distinction between the last two is the whole point:
 *
 * <ul>
 *   <li><b>New key</b> — reserved as {@code IN_PROGRESS}; the caller runs the work and then
 *       {@link #complete}s the reservation in the same transaction.</li>
 *   <li><b>Same key, same fingerprint, {@code COMPLETED}</b> — {@link Reservation.Replay}. The
 *       stored response is returned verbatim and the work is not redone.</li>
 *   <li><b>Same key, different fingerprint</b> — {@code 409 IDEMPOTENCY_KEY_CONFLICT}. The client
 *       reused a key for a different request; there is no correct guess to make.</li>
 *   <li><b>Same key, still {@code IN_PROGRESS}</b> — {@code 409 REQUEST_IN_PROGRESS} with
 *       {@code Retry-After: 1}, rather than waiting on a lock.</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    /** Deletion batch size from §A6. Bounded so the sweeper cannot hold a long lock on the index. */
    static final int PURGE_BATCH = 1_000;

    private final Sql sql;
    private final ReconcileProperties properties;
    private final Clock clock;

    public IdempotencyService(Sql sql, ReconcileProperties properties, Clock clock) {
        this.sql = sql;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The endpoint a key is scoped to.
     *
     * <p>§A1 scopes a key to an endpoint rather than globally, so {@code authorize} and
     * {@code capture} are separate namespaces and one client's key cannot silently suppress
     * another's command. The method is part of the namespace rather than only of the fingerprint:
     * uniqueness is {@code (endpoint, idem_key)}, and putting the method in the fingerprint alone
     * would make two different verbs on one template collide into {@code IDEMPOTENCY_KEY_CONFLICT}
     * instead of being the independent namespaces they are.
     *
     * <p>Paths are templates, never concrete ids — {@code /api/v1/payments/{id}/capture}, not
     * {@code /api/v1/payments/pay_01/capture} — so a key minted for one payment can never be
     * silently accepted for another one.
     */
    public record Route(String method, String pathTemplate) {

        public String key() {
            return method + " " + pathTemplate;
        }
    }

    // The endpoint templates from §A1, named once so a controller cannot mistype the namespace its
    // key lives in: a typo here would silently give two endpoints separate key spaces.
    public static final Route CREATE_PAYMENT = new Route("POST", "/api/v1/payments");
    public static final Route AUTHORIZE_PAYMENT =
            new Route("POST", "/api/v1/payments/{id}/authorize");
    public static final Route CAPTURE_PAYMENT =
            new Route("POST", "/api/v1/payments/{id}/capture");
    public static final Route REFUND_PAYMENT = new Route("POST", "/api/v1/payments/{id}/refund");
    public static final Route FAIL_PAYMENT = new Route("POST", "/api/v1/payments/{id}/fail");
    public static final Route EXECUTE_PAYOUT = new Route("POST", "/api/v1/payouts");

    /** What the service found for this key. */
    public sealed interface Reservation {

        /** The key was unused; the caller must run the work and then {@link #complete}. */
        record Fresh(String recordId) implements Reservation {
        }

        /**
         * The request already ran.
         *
         * @param resourceId the id created the first time, so a replay can repeat the original
         *                   {@code Location} header instead of inventing a second one
         */
        record Replay(int status, String body, String resourceType, String resourceId,
                      String recordId) implements Reservation {
        }
    }

    /**
     * Reserves {@code idemKey} on {@code route}, in the caller's transaction.
     *
     * <p>{@code ON CONFLICT DO NOTHING} is not an optimisation, it is the concurrency argument from
     * §A5: a losing inserter blocks on the unique index until the winner commits, then inserts
     * nothing and reads back a {@code COMPLETED} record. Under {@code READ COMMITTED} the blocking
     * statement unblocks only after the winner's commit, so the follow-up {@code SELECT} takes a
     * fresh snapshot and sees the finished record. No lock is held by this class and none is needed.
     *
     * <p><b>Defect 20 — the expiry test belongs here, not only in the sweeper.</b> The conflict
     * clause updates an existing row only when it is past {@code expires_at}, which is what makes
     * §A6 true by itself rather than by luck. It used to be {@code DO NOTHING}, so a record was
     * replayable until the sweeper happened to delete it: between expiry and the next sweep a
     * client got the old answer, and if the sweeper was disabled or failing it got that answer
     * forever. The retention window was then a property of the scheduler's health rather than of
     * the data, and {@code C-IDEM-06} failed against a fully green suite. Filtering in the
     * {@code SELECT} instead would not have worked — the {@code INSERT} conflicts first and never
     * reaches it — so the check belongs in the conflict clause, where it is atomic with the
     * takeover and cannot race another request doing the same thing.
     *
     * <p>An expired {@code IN_PROGRESS} record is taken over on the same terms the sweeper already
     * deletes it: a command still running after a full retention window is not a case worth
     * preserving, and the alternative is a key that can never be reused.
     *
     * @param rawBody the raw request bytes, hashed before deserialisation per §A2
     */
    public Reservation reserve(Route route, String idemKey, byte[] rawBody, String requestId) {
        String fingerprint = fingerprint(route, rawBody);
        String recordId = Ulid.of("idem");
        Instant expiresAt = clock.instant().plus(properties.idempotency().retention());

        int inserted = sql.sql("""
                INSERT INTO idempotency_records
                    (id, endpoint, idem_key, fingerprint, status, request_id, expires_at)
                VALUES (?, ?, ?, ?, 'IN_PROGRESS', ?, ?)
                ON CONFLICT (endpoint, idem_key) DO UPDATE
                   SET id = EXCLUDED.id,
                       fingerprint = EXCLUDED.fingerprint,
                       status = 'IN_PROGRESS',
                       request_id = EXCLUDED.request_id,
                       created_at = now(),
                       completed_at = NULL,
                       response_status = NULL,
                       response_body = NULL,
                       resource_type = NULL,
                       resource_id = NULL,
                       expires_at = EXCLUDED.expires_at
                 WHERE idempotency_records.expires_at <= ?
                """)
                .params(recordId, route.key(), idemKey, fingerprint, requestId, expiresAt,
                        clock.instant())
                .update();

        return inserted == 1
                ? new Reservation.Fresh(recordId)
                : resolveExisting(route, idemKey, fingerprint);
    }

    /**
     * Stores the response so a later retry is served without redoing the work.
     *
     * <p>Runs in the caller's transaction on purpose. A reservation and its command commit together
     * or not at all, so {@code IN_PROGRESS} is only ever visible to a genuinely concurrent request
     * and never survives a crash — which is exactly the property §A4 relies on.
     *
     * <p>Returns the body <b>as PostgreSQL stored it</b>, and the caller must answer the first
     * request with those bytes rather than the ones it serialised. {@code jsonb} does not keep a
     * document verbatim: it discards key order, de-duplicates object keys and re-renders whitespace.
     * Returning what the database holds is what makes the replay byte-identical, which is the whole
     * guarantee. Returning the locally serialised string would make the first caller and every
     * replayed caller receive two different renderings of the same answer.
     */
    public String complete(
            String recordId, int status, String body, String resourceType, String resourceId) {

        return sql.sql("""
                UPDATE idempotency_records
                   SET status = 'COMPLETED',
                       response_status = ?,
                       response_body = CAST(? AS jsonb),
                       resource_type = ?,
                       resource_id = ?,
                       completed_at = ?
                 WHERE id = ?
                RETURNING response_body::text AS stored_body
                """)
                .params(status, body, resourceType, resourceId, clock.instant(), recordId)
                .optional((rs, rowNum) -> rs.getString("stored_body"))
                .orElseThrow(() -> new DomainException(
                        ProblemCode.INTERNAL_ERROR,
                        "idempotency record " + recordId + " disappeared while it was completed"));
    }

    /**
     * Deletes reservations past their retention window, oldest first, in bounded batches.
     *
     * <p>§A6's trade-off, stated there and repeated here because it is a real limitation rather
     * than an implementation detail: a key presented after the retention window is treated as new
     * and will execute again. Anything longer than that would mean keeping request bodies
     * indefinitely, which is a worse answer than a documented, bounded window.
     */
    public int purgeExpired() {
        int deleted = sql.sql("""
                DELETE FROM idempotency_records
                 WHERE id IN (
                       SELECT id FROM idempotency_records
                        WHERE expires_at < ?
                        ORDER BY created_at, id
                        LIMIT ?)
                """)
                .params(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                        PURGE_BATCH)
                .update();

        if (deleted == PURGE_BATCH) {
            // The batch is full, so there is probably another one behind it. Draining here keeps
            // the sweeper a single call rather than a scheduler that has to guess how many times
            // to fire in a tick; either way the work is bounded per statement.
            deleted += purgeExpired();
        }
        return deleted;
    }

    // ------------------------------------------------------------------ internals

    private Reservation resolveExisting(Route route, String idemKey, String fingerprint) {
        Optional<Record> existing = sql.sql("""
                SELECT id, fingerprint, status, response_status, response_body,
                       resource_type, resource_id
                  FROM idempotency_records
                 WHERE endpoint = ? AND idem_key = ?
                """)
                .params(route.key(), idemKey)
                .optional((rs, rowNum) -> new Record(
                        rs.getString("id"),
                        rs.getString("fingerprint"),
                        rs.getString("status"),
                        rs.getObject("response_status", Integer.class),
                        rs.getString("response_body"),
                        rs.getString("resource_type"),
                        rs.getString("resource_id")));

        if (existing.isEmpty()) {
            // The row vanished between the conflict and this read. The only writer that deletes is
            // the retention sweeper, so this means the key expired between the two statements.
            // Treating it as new is correct: the previous response is gone, so a replay is not
            // available and executing is the only honest answer.
            throw new DomainException(
                    ProblemCode.IDEMPOTENCY_KEY_CONFLICT,
                    "idempotency key '" + idemKey + "' on " + route.key()
                            + " expired while the request was being claimed; retry with a new key");
        }

        Record record = existing.get();
        if (!record.fingerprint().equals(fingerprint)) {
            throw new DomainException(
                    ProblemCode.IDEMPOTENCY_KEY_CONFLICT,
                    "idempotency key '" + idemKey + "' was already used for a different request on "
                            + route.key());
        }
        if ("IN_PROGRESS".equals(record.status())) {
            throw new DomainException(
                    ProblemCode.REQUEST_IN_PROGRESS,
                    "a request with idempotency key '" + idemKey + "' is still executing");
        }
        if (record.responseStatus() == null || record.responseBody() == null) {
            // ck_idem_completed makes this unreachable through the database. It is checked because
            // a silent null here would be replayed as an empty 200, which is worse than an error.
            throw new DomainException(
                    ProblemCode.INTERNAL_ERROR,
                    "idempotency record " + record.id() + " is COMPLETED but has no stored response");
        }
        return new Reservation.Replay(record.responseStatus(), record.responseBody(),
                record.resourceType(), record.resourceId(), record.id());
    }

    /**
     * {@code SHA-256} of the canonical JSON from §A2.
     *
     * <p>The body is hashed <b>raw</b>, before deserialisation, and that is a deliberate
     * inconvenience: two requests differing only in key order or whitespace hash differently and
     * conflict. A client that re-serialises differently on retry has a real ambiguity about which
     * of the two it meant, and treating them as the same request would silently pick one.
     *
     * <p>{@code char(64)} is exactly a hex-encoded {@code SHA-256}, so the digest is stored rather
     * than the request: enough to detect a different request, incapable of leaking one.
     */
    public static String fingerprint(Route route, byte[] rawBody) {
        String bodyHash = sha256(rawBody);
        String canonical = "{\"bodyHash\":\"" + bodyHash
                + "\",\"method\":\"" + route.method()
                + "\",\"pathTemplate\":\"" + route.pathTemplate() + "\"}";
        return sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value == null ? new byte[0] : value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    private record Record(
            String id,
            String fingerprint,
            String status,
            Integer responseStatus,
            String responseBody,
            String resourceType,
            String resourceId) {
    }
}