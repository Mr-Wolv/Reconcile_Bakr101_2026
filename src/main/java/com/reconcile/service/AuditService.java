package com.reconcile.service;

import com.reconcile.shared.Ulid;
import java.util.Map;
import com.reconcile.persistence.jdbc.Sql;
import org.springframework.stereotype.Service;

/**
 * Writes the append-only audit trail.
 *
 * <p>Every state change in this system lands here. The table is protected by a database trigger
 * that refuses {@code UPDATE} and {@code DELETE}, so this class has no update or delete path — not
 * as a policy, but because there is nothing to call.
 *
 * <p>Audit writes participate in the caller's transaction. That is deliberate: an audit row that
 * survives a rolled-back command would describe something that never happened, which is worse than
 * having no record at all.
 *
 * <p>No request or response body is ever written. A payment request can contain a merchant
 * reference that is personal data, and an audit table with a GIN index on its metadata is exactly
 * the place that data should not be.
 */
@Service
public class AuditService {

    private final Sql sql;

    public AuditService(Sql sql) {
        this.sql = sql;
    }

    /**
     * Appends one audit event.
     *
     * @param actorType one of {@code USER}, {@code SERVICE}, {@code WORKER}, {@code WEBHOOK}, {@code SYSTEM}
     */
    public void record(
            String actorType,
            String actorId,
            String action,
            String entityType,
            String entityId,
            String requestId,
            Map<String, Object> metadata) {

        sql.sql("""
                INSERT INTO audit_events
                    (id, actor_type, actor_id, action, entity_type, entity_id, request_id, metadata)
                VALUES (:id, :actorType, :actorId, :action, :entityType, :entityId, :requestId,
                        CAST(:metadata AS jsonb))
                """)
                .param("id", Ulid.of("aud"))
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("action", action)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("requestId", requestId)
                .param("metadata", toJson(metadata))
                .update();
    }

    public void record(String actorType, String actorId, String action, String entityType, String entityId,
                       String requestId) {
        record(actorType, actorId, action, entityType, entityId, requestId, Map.of());
    }

    /**
     * Renders metadata as JSON.
     *
     * <p>Hand-built rather than pulling in a mapper, because the shape is constrained: string keys
     * and scalar values, written by this codebase only. Values are quoted unless they are numeric or
     * boolean, and a quote or backslash inside a string is escaped — an unescaped quote here would
     * produce invalid JSON and fail the whole audit insert, losing the record of the event entirely.
     */
    private static String toJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escape(entry.getKey())).append("\":");
            Object value = entry.getValue();
            if (value == null) {
                json.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                json.append(value);
            } else {
                json.append('"').append(escape(String.valueOf(value))).append('"');
            }
        }
        return json.append('}').toString();
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}