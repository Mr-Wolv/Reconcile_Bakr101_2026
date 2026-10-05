package com.reconcile.service;

import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.shared.DomainException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Reads the audit trail (spec 06 §1).
 *
 * <p>Audit rows are written append-only by {@link AuditService} and refused by a database trigger,
 * so this class has no write path at all — it cannot correct a mistaken audit record, and that is
 * the point. An audit trail that can be edited is not one.
 *
 * <p>Pagination is keyset on {@code (occurred_at, id)}, never {@code OFFSET}. An offset on an
 * append-only table being appended to while it is read skips rows and repeats them, which for a
 * financial audit query is not a cosmetic defect: the auditor is looking for the one event that
 * explains a discrepancy.
 */
@Service
public class AuditQueryService {

    /** A page cap, so one request cannot ask for the whole table. */
    public static final int MAX_LIMIT = 500;

    private final Sql sql;

    public AuditQueryService(Sql sql) {
        this.sql = sql;
    }

    /** One audit row, as read back. */
    public record AuditEventView(
            String id,
            Instant occurredAt,
            String actorType,
            String actorId,
            String action,
            String entityType,
            String entityId,
            String requestId,
            String metadata) {
    }

    /** The filters {@code GET /api/v1/audit/events} accepts. Every one is optional. */
    public record Filter(
            String action,
            String entityType,
            String entityId,
            String actorType,
            Instant from,
            Instant to,
            String requestId,
            Instant after,
            String afterId,
            Integer limit) {
    }

    /**
     * The trail for one entity, newest first.
     *
     * <p>The same query the payment timeline endpoint uses (spec 06 §1), exposed directly so an
     * auditor can pull the trail for any entity type without going through a payment-shaped API.
     */
    public List<AuditEventView> forEntity(String entityType, String entityId) {
        return query("entity_type = :entityType AND entity_id = :entityId",
                List.of(
                        param("entityType", entityType),
                        param("entityId", entityId)),
                null);
    }

    /** The filtered trail, newest first, one page. */
    public List<AuditEventView> events(Filter filter) {
        List<String> clauses = new ArrayList<>();
        List<Object[]> params = new ArrayList<>();

        addIfPresent(clauses, params, "action", filter.action());
        addIfPresent(clauses, params, "entity_type", filter.entityType());
        addIfPresent(clauses, params, "entity_id", filter.entityId());
        addIfPresent(clauses, params, "actor_type", filter.actorType());
        addIfPresent(clauses, params, "request_id", filter.requestId());

        if (filter.from() != null) {
            clauses.add("occurred_at >= :from");
            params.add(new Object[] {"from", filter.from()});
        }
        if (filter.to() != null) {
            clauses.add("occurred_at < :to");
            params.add(new Object[] {"to", filter.to()});
        }
        // Keyset, not OFFSET. Both halves matter: occurred_at alone is not unique, so paging on the
        // timestamp alone silently skips every row that shares it with another.
        //
        // Strictly *less than*, because the ordering is DESC. Getting this backwards is the classic
        // keyset bug: the comparison still returns rows, so nothing looks broken, but the "next
        // page" is computed from the wrong end of the list and the caller silently walks the trail
        // in the wrong direction.
        if (filter.after() != null) {
            clauses.add("(occurred_at, id) < (:after, :afterId)");
            params.add(new Object[] {"after", filter.after()});
            params.add(new Object[] {"afterId", filter.afterId() == null ? "" : filter.afterId()});
        }

        int limit = filter.limit() == null ? 100 : filter.limit();
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and " + MAX_LIMIT + ", got " + limit);
        }

        return query(String.join(" AND ", clauses), params, limit);
    }

    private List<AuditEventView> query(String where, List<Object[]> params, Integer limit) {
        if (where.isBlank()) {
            where = "TRUE";
        }
        String sqlText = """
                SELECT id, occurred_at, actor_type, actor_id, action, entity_type, entity_id,
                       request_id, metadata
                  FROM audit_events
                 WHERE %s
                 ORDER BY occurred_at DESC, id DESC
                 LIMIT %d
                """.formatted(where, limit == null ? MAX_LIMIT : limit);

        var statement = sql.sql(sqlText);
        for (Object[] pair : params) {
            statement = statement.param((String) pair[0], pair[1]);
        }
        return statement.list((rs, rowNum) -> new AuditEventView(
                rs.getString("id"),
                Sql.instant(rs, "occurred_at"),
                rs.getString("actor_type"),
                rs.getString("actor_id"),
                rs.getString("action"),
                rs.getString("entity_type"),
                rs.getString("entity_id"),
                rs.getString("request_id"),
                rs.getString("metadata")));
    }

    private static void addIfPresent(List<String> clauses, List<Object[]> params,
            String column, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String name = column.replace("_", "") + "Filter";
        clauses.add(column + " = :" + name);
        params.add(new Object[] {name, value});
    }

    private static Object[] param(String name, Object value) {
        return new Object[] {name, value};
    }

    /**
     * The entity types the application actually writes an audit trail against.
     *
     * <p>Kept as a whitelist rather than passed through into the query, because the distinction it
     * buys is worth having: an unknown type is a {@code 404} ("this system does not audit that
     * thing") and a known type with no events is an empty {@code 200} ("that thing has not
     * happened yet"). Collapsing the two would make a caller unable to tell a typo from a quiet
     * entity.
     *
     * <p><b>This list used to be wrong, and silently so.</b> It carried {@code PAYOUT},
     * {@code LEDGER_TRANSACTION} and {@code ACCOUNT} — none of which is ever written — while
     * omitting {@code PAYOUT_RECORD} and {@code PROVIDER}, both of which are. So the trail for a
     * payout record, one of the most important things to be able to audit in this system, returned
     * {@code 404}, while three types that could never match returned a confident empty list. The
     * near-miss between {@code PAYOUT} and {@code PAYOUT_RECORD} is very likely how it happened.
     * {@code OperationsApiIT.entityTrailCoversEveryTypeTheApplicationWrites} now asserts the list
     * against the database rather than against a copy of itself, so the next divergence fails.
     *
     * <p>Not an injection guard: the query binds {@code :entityType}. It was once described as one,
     * which was wrong.
     */
    /**
     * The accepted entity types, as a single string, so the generated OpenAPI document can publish
     * them instead of a bare {@code type: string} — an annotation value has to be a compile-time
     * constant, which rules out deriving one from a collection at runtime.
     *
     * <p>The list below is parsed from this string rather than written out separately. Two literals
     * would be two places to update, and the failure mode would be quiet: the endpoint would accept
     * a type the published contract did not list.
     */
    public static final String ENTITY_TYPE_DOC = """
            PAYMENT, PAYOUT_RECORD, PROVIDER, PROVIDER_EVENT, SETTLEMENT_RECORD, \
            RECONCILIATION_BATCH, RECONCILIATION_RESULT, RECONCILIATION_CASE, IDEMPOTENCY_RECORD""";

    /** The accepted entity types, derived from {@link #ENTITY_TYPE_DOC}. */
    public static final List<String> ENTITY_TYPES = Arrays.stream(ENTITY_TYPE_DOC.split(","))
            .map(String::trim)
            .filter(token -> !token.isEmpty())
            .toList();

    public static boolean isKnownEntityType(String entityType) {
        return entityType != null && ENTITY_TYPES.contains(entityType.toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * Validates and normalises an entity type from the URL.
     *
     * <p>Case-insensitive on purpose. The stored values are SCREAMING_SNAKE tokens that are an
     * internal representation, and making a caller reproduce that casing exactly would put a
     * storage detail into the public contract: renaming {@code PAYOUT_RECORD} would silently break
     * every client. Normalising is not aliasing — an unrecognised type is still a {@code 404}, and
     * the canonical form is what reaches the query.
     */
    public String requireKnownEntityType(String entityType) {
        if (!isKnownEntityType(entityType)) {
            throw DomainException.notFound("audit entity type", entityType);
        }
        return entityType.toUpperCase(java.util.Locale.ROOT);
    }
}