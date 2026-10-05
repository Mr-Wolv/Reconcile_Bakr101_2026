package com.reconcile.api;

import com.reconcile.service.AuditQueryService;
import com.reconcile.service.HealthService;
import com.reconcile.persistence.jdbc.Sql;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operational surfaces of spec 06 §1: health, the audit read API, and a summary counter.
 *
 * <p>All three exist because the alternative is worse. Without {@code /health} an operator learns
 * that the ledger is broken from a customer. Without the audit read API, the trail can only be
 * inspected with a psql session. Without the summary, "how bad is it right now" needs a hand-written
 * query and an opinion about which tables to count.
 */
@RestController
@RequestMapping("/api/v1")
public class OperationsController {

    private final HealthService health;
    private final AuditQueryService audit;
    private final Sql sql;

    public OperationsController(HealthService health, AuditQueryService audit, Sql sql) {
        this.health = health;
        this.audit = audit;
        this.sql = sql;
    }

    /**
     * Liveness is not health.
     *
     * <p>{@code 503} with the failing checks named, not {@code 200} with a sad message: a load
     * balancer that reads {@code 200} from a process whose ledger does not verify will keep sending
     * it traffic, and the breach gets worse while the status page stays green.
     */
    @GetMapping("/health")
    public ResponseEntity<HealthService.Report> health() {
        HealthService.Report report = health.check();
        return ResponseEntity.status(report.healthy() ? 200 : 503).body(report);
    }

    @GetMapping("/audit/events")
    public List<AuditQueryService.AuditEventView> auditEvents(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String actorType,
            @RequestParam(required = false) java.time.Instant from,
            @RequestParam(required = false) java.time.Instant to,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) java.time.Instant after,
            @RequestParam(required = false) String afterId,
            @RequestParam(required = false) Integer limit) {

        return audit.events(new AuditQueryService.Filter(
                action, entityType, entityId, actorType, from, to, requestId, after, afterId, limit));
    }

    /**
     * The trail for one entity.
     *
     * <p>The {@code type} allowance is generated from the service's own list rather than written
     * out here, so the published contract cannot drift from the values the endpoint accepts. A
     * caller reading the document sees the nine tokens that exist; a tenth is a {@code 404}.
     */
    @Operation(summary = "Audit trail for a single entity, by entity type and id")
    @GetMapping("/audit/entities/{type}/{id}")
    public List<AuditQueryService.AuditEventView> entityTrail(
            @Parameter(description = "Entity type token. Case-insensitive: `payment` and "
                    + "`PAYMENT` are the same request. Any other value is a 404. Accepted: "
                    + AuditQueryService.ENTITY_TYPE_DOC + ".")
            @PathVariable String type,
            @Parameter(description = "The entity's id, as stored in `audit_events.entity_id`")
            @PathVariable String id) {

        return audit.forEntity(audit.requireKnownEntityType(type), id);
    }

    /**
     * Counts, not a dashboard.
     *
     * <p>Every figure here is a {@code COUNT} over an indexed column. Nothing is cached and nothing
     * is precomputed: a summary that is stale is worse than one that is slow, because it is trusted.
     */
    @GetMapping("/metrics/summary")
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();

        Map<String, Integer> paymentsByState = new LinkedHashMap<>();
        for (var row : sql.sql("""
                SELECT state, COUNT(*) AS total FROM payments GROUP BY state ORDER BY state
                """).list((rs, rowNum) -> new String[] {
                        rs.getString("state"), Integer.toString(rs.getInt("total")) })) {
            paymentsByState.put(row[0], Integer.parseInt(row[1]));
        }
        out.put("paymentsByState", paymentsByState);

        Map<String, Integer> casesByStatus = new LinkedHashMap<>();
        for (var row : sql.sql("""
                SELECT status, COUNT(*) AS total FROM reconciliation_cases GROUP BY status
                ORDER BY status
                """).list((rs, rowNum) -> new String[] {
                        rs.getString("status"), Integer.toString(rs.getInt("total")) })) {
            casesByStatus.put(row[0], Integer.parseInt(row[1]));
        }
        out.put("casesByStatus", casesByStatus);

        out.put("openCases", count("""
                SELECT COUNT(*) AS total FROM reconciliation_cases
                 WHERE status IN ('OPEN','INVESTIGATING')
                """));

        out.put("failedProviderEvents", count("""
                SELECT COUNT(*) AS total FROM provider_events WHERE status = 'FAILED'
                """));

        out.put("runningBatches", count("""
                SELECT COUNT(*) AS total FROM reconciliation_batches WHERE status = 'RUNNING'
                """));

        // The one number an operator actually acts on: money the provider says we are owed and
        // our own ledger cannot account for.
        Long unreconciled = sql.sql("""
                SELECT COALESCE(SUM(delta_minor), 0) AS total
                  FROM reconciliation_results
                 WHERE delta_minor IS NOT NULL
                   AND outcome <> 'MATCHED'
                """).optional((rs, rowNum) -> rs.getLong("total")).orElse(0L);
        out.put("unreconciledDeltaMinor", unreconciled);

        return out;
    }

    private int count(String statement) {
        return sql.sql(statement).optional((rs, rowNum) -> rs.getInt("total")).orElse(0);
    }
}