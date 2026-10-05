package com.reconcile.api;

import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.service.ReconciliationCaseService;
import com.reconcile.service.ReconciliationService;
import com.reconcile.service.ReconciliationService.BatchRequest;
import com.reconcile.service.ReconciliationService.SubjectMode;
import com.reconcile.service.ReconciliationWorker;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reconciliation endpoints, per {@code docs/spec/06 §1}.
 *
 * <p>{@code async: false} runs a batch inline and answers with the finished summary. That exists for
 * two reasons: the CI scenario needs a deterministic completed batch without polling, and an
 * operator reconciling a single small window should not have to wait for a scheduler tick to learn
 * whether it worked.
 */
@RestController
@RequestMapping("/api/v1/reconciliation")
public class ReconciliationController {

    private final ReconciliationService reconciliation;
    private final ReconciliationWorker worker;
    private final ReconciliationCaseService cases;
    private final Sql sql;

    public ReconciliationController(
            ReconciliationService reconciliation,
            ReconciliationWorker worker,
            ReconciliationCaseService cases,
            Sql sql) {

        this.reconciliation = reconciliation;
        this.worker = worker;
        this.cases = cases;
        this.sql = sql;
    }

    public record CreateBatchRequest(
            @NotBlank String provider,
            @NotNull Instant windowStart,
            @NotNull Instant windowEnd,
            SubjectMode subjectMode,
            Boolean async) {

        public CreateBatchRequest {
            if (subjectMode == null) {
                subjectMode = SubjectMode.BOTH;
            }
        }
    }

    @PostMapping("/batches")
    public ResponseEntity<BatchView> createBatch(
            @Valid @RequestBody CreateBatchRequest request) {

        String batchId = reconciliation.createBatch(new BatchRequest(
                request.provider(),
                request.windowStart(),
                request.windowEnd(),
                request.subjectMode(),
                "api",
                RequestId.current()));

        boolean async = request.async() == null || request.async();
        if (!async) {
            worker.runBatch(batchId);
        }
        return ResponseEntity.status(async ? 202 : 200).body(batch(batchId));
    }

    @GetMapping("/batches")
    public List<BatchView> list() {
        return sql.sql("SELECT id FROM reconciliation_batches ORDER BY started_at DESC, id")
                .list((rs, rowNum) -> batch(rs.getString("id")));
    }

    @GetMapping("/batches/{id}")
    public BatchView get(@PathVariable String id) {
        return batch(id);
    }

    @GetMapping("/batches/{id}/results")
    public List<ResultView> results(@PathVariable String id) {
        return sql.sql("""
                SELECT subject_key, outcome, internal_payment_id, provider_transaction_id,
                       delta_minor, case_id, differences
                  FROM reconciliation_results
                 WHERE batch_id = ?
                 ORDER BY subject_key
                """)
                .param(id)
                .list((rs, rowNum) -> new ResultView(
                        rs.getString("subject_key"),
                        rs.getString("outcome"),
                        rs.getString("internal_payment_id"),
                        rs.getString("provider_transaction_id"),
                        (Long) rs.getObject("delta_minor"),
                        rs.getString("case_id"),
                        rs.getString("differences")));
    }

    @GetMapping("/cases")
    public List<ReconciliationCaseService.CaseView> openCases() {
        return cases.open();
    }

    public record InvestigateRequest(@NotBlank String assignedTo) {
    }

    @PostMapping("/cases/{id}/investigate")
    public ReconciliationCaseService.CaseView investigate(
            @PathVariable String id, @Valid @RequestBody InvestigateRequest request) {

        cases.investigate(id, request.assignedTo(), "api", RequestId.current());
        return cases.open().stream()
                .filter(c -> c.id().equals(id))
                .findFirst()
                .orElseThrow(() -> com.reconcile.shared.DomainException.notFound("case", id));
    }

    public record ResolveRequest(
            @NotNull @Size(min = 20, max = 2000) String resolutionNote,
            String adjustmentLedgerTransactionId) {
    }

    @PostMapping("/cases/{id}/resolve")
    public ResponseEntity<Void> resolve(
            @PathVariable String id, @Valid @RequestBody ResolveRequest request) {

        cases.resolve(id, request.resolutionNote(), request.adjustmentLedgerTransactionId(),
                "api", RequestId.current());
        return ResponseEntity.noContent().build();
    }

    public record WriteOffRequest(
            @NotNull @Size(min = 20, max = 2000) String resolutionNote,
            @NotNull ReconciliationCaseService.WriteOffAction resolutionAction) {
    }

    @PostMapping("/cases/{id}/write-off")
    public ResponseEntity<Void> writeOff(
            @PathVariable String id, @Valid @RequestBody WriteOffRequest request) {

        cases.writeOff(id, request.resolutionNote(), request.resolutionAction(),
                "api", RequestId.current());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ views

    public record BatchView(
            String id,
            String provider,
            Instant windowStart,
            Instant windowEnd,
            String subjectMode,
            String status,
            int totalSubjects,
            int processedSubjects,
            Summary totals) {
    }

    public record Summary(
            int matched,
            int matchedNotSettled,
            int mismatched,
            int missingOnProvider,
            int missingInternal,
            int duplicates,
            int ambiguous,
            long grossDeltaMinor,
            int casesOpened,
            int casesReused) {
    }

    public record ResultView(
            String subjectKey,
            String outcome,
            String internalPaymentId,
            String providerTransactionId,
            Long deltaMinor,
            String caseId,
            String differences) {
    }

    private BatchView batch(String id) {
        return sql.sql("""
                SELECT id, provider, window_start, window_end, subject_mode, status,
                       total_subjects, processed_subjects, matched_count, matched_not_settled_count,
                       mismatched_count, missing_on_provider_count, missing_internal_count,
                       duplicate_count, ambiguous_count, gross_delta_minor, cases_opened, cases_reused
                  FROM reconciliation_batches WHERE id = ?
                """)
                .param(id)
                .optional((rs, rowNum) -> new BatchView(
                        rs.getString("id"),
                        rs.getString("provider"),
                        Sql.instant(rs, "window_start"),
                        Sql.instant(rs, "window_end"),
                        rs.getString("subject_mode"),
                        rs.getString("status"),
                        rs.getInt("total_subjects"),
                        rs.getInt("processed_subjects"),
                        new Summary(
                                rs.getInt("matched_count"),
                                rs.getInt("matched_not_settled_count"),
                                rs.getInt("mismatched_count"),
                                rs.getInt("missing_on_provider_count"),
                                rs.getInt("missing_internal_count"),
                                rs.getInt("duplicate_count"),
                                rs.getInt("ambiguous_count"),
                                rs.getLong("gross_delta_minor"),
                                rs.getInt("cases_opened"),
                                rs.getInt("cases_reused"))))
                .orElseThrow(() -> com.reconcile.shared.DomainException.notFound("batch", id));
    }
}