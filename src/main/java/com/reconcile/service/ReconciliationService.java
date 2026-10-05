package com.reconcile.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconcile.payment.PaymentState;
import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.reconciliation.ReconciliationClassification;
import com.reconcile.reconciliation.ReconciliationClassifier;
import com.reconcile.reconciliation.ReconciliationOutcome;
import com.reconcile.reconciliation.ReconciliationSubject;
import com.reconcile.reconciliation.SettlementValidity;
import com.reconcile.shared.CurrencyCode;
import com.reconcile.shared.Money;
import com.reconcile.shared.Ulid;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciliation: does our record of the money agree with the provider's?
 *
 * <p>The part worth arguing for is {@link #expectedView}: the internal side is derived from the
 * <b>ledger</b>, not from the payment row (ADR-0005, spec 04 §3). If the expectation came from
 * {@code payments.amount_minor}, a bug that wrote the wrong amount into both the payment and the
 * ledger would reconcile clean forever — the comparison would be checking one copy of the mistake
 * against another. Deriving it from the ledger means the two sides are genuinely independent, so
 * agreement means something.
 *
 * <p>A batch never posts to the ledger. Corrections are separate, explicit, case-attached actions;
 * silently "fixing" money during reconciliation would destroy the audit trail, which is the one
 * thing this whole system exists to preserve (spec 04 §7).
 */
@Service
public class ReconciliationService {

    /** Which side supplies the population of subjects. */
    public enum SubjectMode {
        INTERNAL_LEDGER,
        PROVIDER_ONLY,
        BOTH
    }

    /** A batch's identity and scope. */
    public record BatchRequest(
            String provider,
            Instant windowStart,
            Instant windowEnd,
            SubjectMode subjectMode,
            String startedBy,
            String requestId) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Sql sql;
    private final AuditService audit;
    private final Clock clock;

    public ReconciliationService(Sql sql, AuditService audit, Clock clock) {
        this.sql = sql;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ batches

    /**
     * Creates a batch and freezes its subject population.
     *
     * <p>Snapshotting at creation is what makes a resumed batch comparable: a re-run over a window
     * whose data has since changed would otherwise classify a different set of subjects and its
     * results would not be comparable with the first attempt's.
     */
    @Transactional
    public String createBatch(BatchRequest request) {
        String batchId = Ulid.of("rbat");

        sql.sql("""
                INSERT INTO reconciliation_batches
                    (id, provider, window_start, window_end, subject_mode, status, started_by,
                     request_id, total_subjects)
                VALUES (?, ?, ?, ?, ?, 'RUNNING', ?, ?, 0)
                """)
                .params(batchId, request.provider(), request.windowStart(), request.windowEnd(),
                        request.subjectMode().name(), request.startedBy(), request.requestId())
                .update();

        int subjects = snapshotSubjects(batchId, request);
        sql.sql("UPDATE reconciliation_batches SET total_subjects = ? WHERE id = ?")
                .params(subjects, batchId)
                .update();

        audit.record("USER", request.startedBy(), "RECONCILIATION_BATCH_STARTED",
                "RECONCILIATION_BATCH", batchId, request.requestId(),
                Map.of("subjects", subjects, "subjectMode", request.subjectMode().name()));
        return batchId;
    }

    private int snapshotSubjects(String batchId, BatchRequest request) {
        List<SubjectRow> rows = enumerateSubjects(request);
        int count = 0;
        for (SubjectRow row : rows) {
            sql.sql("""
                    INSERT INTO reconciliation_subjects
                        (batch_id, subject_key, provider, external_transaction_id, side)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (batch_id, subject_key) DO NOTHING
                    """)
                    .params(batchId, row.subjectKey(request.provider()), request.provider(),
                            row.externalTransactionId(), row.side())
                    .update();
            count++;
        }
        return count;
    }

    /**
     * The subject population, in a fixed order.
     *
     * <p>Sorted, because determinism is a stated requirement (spec 04 §4): the same window must
     * produce the same subjects in the same order every time, or the golden-fixture test is
     * impossible and two runs of one batch are not comparable.
     */
    private List<SubjectRow> enumerateSubjects(BatchRequest request) {
        boolean includeInternal = request.subjectMode() != SubjectMode.PROVIDER_ONLY;
        boolean includeProvider = request.subjectMode() != SubjectMode.INTERNAL_LEDGER;

        List<SubjectRow> rows = new ArrayList<>();
        if (includeInternal) {
            for (String externalId : capturedPaymentExternalIds(request)) {
                rows.add(new SubjectRow(externalId, "BOTH"));
            }
        }
        if (includeProvider) {
            for (String externalId : providerExternalIds(request)) {
                if (rows.stream().noneMatch(row -> row.externalTransactionId().equals(externalId))) {
                    rows.add(new SubjectRow(externalId, "PROVIDER"));
                }
            }
        }
        rows.sort(Comparator.comparing(SubjectRow::externalTransactionId));
        return rows;
    }

    private List<String> capturedPaymentExternalIds(BatchRequest request) {
        return sql.sql("""
                SELECT DISTINCT provider_transaction_id
                  FROM payments
                 WHERE provider = ?
                   AND provider_transaction_id IS NOT NULL
                   AND captured_at >= ? AND captured_at < ?
                 ORDER BY provider_transaction_id
                """)
                .params(request.provider(), request.windowStart(), request.windowEnd())
                .list((rs, rowNum) -> rs.getString("provider_transaction_id"));
    }

    private List<String> providerExternalIds(BatchRequest request) {
        // Two ways a transaction belongs to a window, and both are needed.
        //
        // A transaction we captured has a real `captured_at` - our own, the instant clearing was
        // credited - and is scoped by it.
        //
        // A transaction the provider named only in a settlement has a NULL `captured_at` on
        // purpose, because no capture time was ever given to us and inventing one from the
        // settlement date would put a fabricated timestamp into the column that decides window
        // membership. Those are scoped by the settlement that mentions them, which is a window we
        // really do know. Excluding them - as the single `captured_at` predicate did - left them
        // outside every batch forever: a provider statement nothing could ever reconcile against.
        //
        // The settlement-date comparison is *inclusive* on both ends, and that is not a
        // convenience. `settlement_date` is a calendar day while the window is a half-open range of
        // instants, so a window ending at "now + 1 hour" ends on today's date: written as
        // `< end`, which is the natural half-open habit, the predicate evaluates to
        // `today < today` and silently drops every settlement dated today. Caught by
        // C-RECON-12, which is the only reason it was ever executed.
        return sql.sql("""
                SELECT DISTINCT p.provider_transaction_id
                  FROM provider_transactions p
                 WHERE p.provider = ?
                   AND ( (p.captured_at >= ? AND p.captured_at < ?)
                         OR (p.captured_at IS NULL
                             AND EXISTS (SELECT 1
                                           FROM settlement_record_lines l
                                           JOIN settlement_records s ON s.id = l.settlement_record_id
                                          WHERE l.provider_transaction_ref = p.id
                                            AND s.provider = ?
                                            AND s.settlement_date >= CAST(? AS date)
                                            AND s.settlement_date <= CAST(? AS date))) )
                 ORDER BY p.provider_transaction_id
                """)
                .params(request.provider(), request.windowStart(), request.windowEnd(),
                        request.provider(), request.windowStart(), request.windowEnd())
                .list((rs, rowNum) -> rs.getString("provider_transaction_id"));
    }

    // ------------------------------------------------------------------ processing

    /**
     * Processes one unprocessed subject, in its own transaction.
     *
     * @return {@code true} when a result was written, {@code false} when the subject was already
     *         done — which is how a resumed batch skips its own completed work
     */
    @Transactional
    public boolean processSubject(String batchId, String subjectKey, String provider,
            String externalTransactionId) {

        ReconciliationSubject subject = loadSubject(provider, externalTransactionId);
        ReconciliationClassification classification = ReconciliationClassifier.classify(subject);

        String resultId = Ulid.of("rres");

        // The case is decided BEFORE the result is written, not after.
        //
        // reconciliation_results is append-only - a trigger refuses UPDATE and DELETE - so case_id
        // has to be part of the INSERT. Deciding it first also means a result row can never exist
        // claiming a case that was never opened. The case's own foreign key points back at the
        // result, so the two ids are allocated here and the result row goes in first.
        CasePlan plan = classification.requiresCase()
                ? planCase(batchId, resultId, classification.outcome(), subject)
                : null;

        int inserted = sql.sql("""
                INSERT INTO reconciliation_results
                    (id, batch_id, subject_key, outcome, internal_payment_id,
                     provider_transaction_id, expected_gross_minor, expected_fee_minor,
                     expected_net_minor, expected_currency, expected_status,
                     actual_gross_minor, actual_fee_minor, actual_net_minor,
                     actual_currency, actual_status, delta_minor, differences, case_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT (batch_id, subject_key) DO NOTHING
                """)
                .params(resultId, batchId, subjectKey, classification.outcome().name(),
                        internalPaymentId(subject),
                        externalTransactionId,
                        classification.expectedGross().map(Money::amountMinor).orElse(null),
                        expectedAmount(subject, true),
                        expectedAmount(subject, false),
                        expectedCurrency(subject),
                        subject.internal().isEmpty() ? null : subject.internal().get(0).status().name(),
                        classification.actualGross().map(Money::amountMinor).orElse(null),
                        providerAmount(subject, true),
                        providerAmount(subject, false),
                        actualCurrency(subject),
                        actualStatus(subject),
                        classification.deltaMinor().orElse(null),
                        differencesJson(classification),
                        plan == null ? null : plan.caseId())
                .update();

        if (inserted == 0) {
            return false;
        }

        if (plan != null) {
            // Both branches matter: a reused case still has to record the re-detection, or
            // occurrence_count would silently stop counting the second time an issue recurs.
            openCase(plan, batchId, resultId, subject);
        }

        sql.sql("UPDATE reconciliation_batches SET processed_subjects = processed_subjects + 1 "
                + "WHERE id = ?")
                .param(batchId)
                .update();

        audit.record("SERVICE", "reconciliation", "RECONCILIATION_SUBJECT_CLASSIFIED",
                "RECONCILIATION_RESULT", resultId, null,
                Map.of("batchId", batchId, "outcome", classification.outcome().name(),
                        "subjectKey", subjectKey));
        return true;
    }

    /** Which case this result belongs to, and whether it still has to be created. */
    private record CasePlan(String caseId, boolean newCase, boolean reused) {
    }

    private CasePlan planCase(String batchId, String resultId, ReconciliationOutcome reason,
            ReconciliationSubject subject) {

        String paymentId = internalPaymentId(subject);
        // Deliberately not filtered on status. One discrepancy on one transaction gets one case,
        // whatever state that case is in: a case an operator already resolved and a re-run that
        // finds the same difference again are the same problem, and opening a second one is how a
        // reconciliation system becomes noise its users switch off. The evidence that it recurred
        // is occurrence_count and the case events, not a fresh row.
        return findCase(paymentId, subject.externalTransactionId(), reason.name())
                .map(existing -> new CasePlan(existing, false, true))
                .orElseGet(() -> new CasePlan(Ulid.of("rcs"), true, false));
    }

    /**
     * The case covering this subject and reason, whatever state it is in.
     *
     * <p>One lookup, used both when planning and when recovering from a lost insert race, so the
     * two can never disagree about what "the case for this subject" means.
     *
     * <p>{@code COALESCE(payment_id, '~')} rather than {@code payment_id} because in PostgreSQL a
     * NULL in a unique index compares as distinct from every other NULL - which is exactly why the
     * index itself is written that way.
     */
    private Optional<String> findCase(String paymentId, String providerTransactionId, String reason) {
        return sql.sql("""
                SELECT id FROM reconciliation_cases
                 WHERE COALESCE(payment_id, '~') = COALESCE(?, '~')
                   AND provider_transaction_id = ?
                   AND reason = ?
                 ORDER BY detected_at, id
                 LIMIT 1
                """)
                .params(paymentId, providerTransactionId, reason)
                .optional((rs, rowNum) -> rs.getString("id"));
    }

    /**
     * Creates the case a result points at, or records a re-detection on an existing one.
 *
     * <p>Reuse rather than duplicate is a hard operational requirement, not a nicety: the first
 * * version of a reconciliation system that opens a fresh case on every re-run is deleted by its
 * users within a week. {@code ux_case_open_subject_reason} makes the invariant a database property;
 * the increment below is what turns a re-detection into evidence rather than noise.
     */
    private void openCase(CasePlan plan, String batchId, String resultId,
            ReconciliationSubject subject) {

        ReconciliationOutcome reason = reasonFor(batchId, resultId);

        if (plan.reused()) {
            recordRecurrence(batchId, plan.caseId(), reason);
            return;
        }

        String paymentId = internalPaymentId(subject);
        int inserted = sql.sql("""
                INSERT INTO reconciliation_cases
                    (id, batch_id, result_id, payment_id, provider_transaction_id, reason,
                     severity, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN')
                ON CONFLICT DO NOTHING
                """)
                .params(plan.caseId(), batchId, resultId, paymentId,
                        subject.externalTransactionId(), reason.name(), reason.severity().name())
                .update();

        if (inserted == 0) {
            // Another worker created the same case between our plan and this insert. `planCase` is
            // a check-then-act and cannot be made atomic on its own; the unique index is what makes
            // the invariant true, and ON CONFLICT is what stops losing that race from throwing.
            // Before this, two batches over overlapping windows could both find no open case, both
            // insert, and the loser took an unhandled DuplicateKeyException that aborted its whole
            // subject transaction - losing the result it had just written.
            String existing = findCase(paymentId, subject.externalTransactionId(), reason.name())
                    .orElse(null);
            if (existing != null) {
                recordRecurrence(batchId, existing, reason);
            }
            return;
        }

        sql.sql("UPDATE reconciliation_batches SET cases_opened = cases_opened + 1 WHERE id = ?")
                .param(batchId)
                .update();
        appendCaseEvent(plan.caseId(), "OPEN", "opened: " + reason.name());
    }

    /**
     * Records that an already-open case was found again.
     *
     * <p>The evidence that a problem recurs is {@code occurrence_count} and the case events, not a
     * second row. A reconciliation system that opens a fresh case on every run is one its users
     * switch off.
     *
     * @param batchId the batch currently running, which is where {@code cases_reused} belongs -
     *                even when the case itself was first detected by an earlier batch
     */
    private void recordRecurrence(String batchId, String caseId, ReconciliationOutcome reason) {
        sql.sql("UPDATE reconciliation_cases SET occurrence_count = occurrence_count + 1, "
                + "updated_at = ? WHERE id = ?")
                .params(clock.instant(), caseId)
                .update();
        sql.sql("UPDATE reconciliation_batches SET cases_reused = cases_reused + 1 WHERE id = ?")
                .param(batchId)
                .update();

        // Recurrence is not a transition at all: nothing moved. It is an observation about a case
        // that is already in the state it is in, so the row is written from the case's actual
        // status to that same status, and the note says why the row exists. appendCaseEvent reads
        // the real status from the case row, so this is F-08 fixed once rather than per caller.
        appendCaseEvent(caseId, caseStatus(caseId),
                "re-detected: " + reason.name() + " (no status change; occurrence only)");
    }

    /** The case's current status. The authority for {@code case_events.from_status}. */
    private String caseStatus(String caseId) {
        return sql.sql("SELECT status FROM reconciliation_cases WHERE id = ?")
                .param(caseId)
                .optional((rs, rowNum) -> rs.getString("status"))
                .orElse("OPEN");
    }

    /** Reads back the reason the result row was written with, so the case can never disagree. */
    private ReconciliationOutcome reasonFor(String batchId, String resultId) {
        return sql.sql("SELECT outcome FROM reconciliation_results WHERE id = ?")
                .param(resultId)
                .optional((rs, rowNum) -> ReconciliationOutcome.valueOf(rs.getString("outcome")))
                .orElseThrow();
    }

    /**
     * Appends one entry to a case's immutable history, recording where the case actually WAS.
 *
 * <p>{@code from_status} is read from the case row rather than supplied, which is the whole of F-08.
 * It used to be the literal {@code 'OPEN'} in the INSERT. For an open case that happens to be
 * correct, which is why nothing failed and why it survived a green suite; for a case an operator had
 * already written off or resolved it wrote a transition out of a state the case had never occupied.
 * An investigation timeline that describes transitions nobody made is worse than no timeline,
 * because it is trusted.
 *
 * <p>Reading it here rather than at each call site is deliberate: every caller of this method
 * writes history, and any one of them hard-coding a source state would reintroduce the same defect.
 * The case row is the only authority on where the case is.
 */
    private void appendCaseEvent(String caseId, String toStatus, String note) {
        String fromStatus = sql.sql("SELECT status FROM reconciliation_cases WHERE id = ?")
                .param(caseId)
                .optional((rs, rowNum) -> rs.getString("status"))
                // No row means the INSERT that creates the case is still in flight; its caller opens
                // the case first and never reaches here in that state. 'OPEN' is the safe answer for
                // the impossible case rather than a silent NULL.
                .orElse("OPEN");

        sql.sql("""
                INSERT INTO case_events
                    (id, case_id, from_status, to_status, note, actor_type, actor_id)
                VALUES (?, ?, ?, ?, ?, 'SYSTEM', 'reconciliation')
                """)
                .params(Ulid.of("cev"), caseId, fromStatus, toStatus, note)
                .update();
    }

    // ------------------------------------------------------------------ the expected view

    /**
     * Loads both sides for one subject.
     *
     * <p>Multiplicity is preserved rather than collapsed: two provider records or two internal
     * payments for one id are outcomes in their own right, and a query that returned only the first
     * of each would report {@code MATCHED} for what is actually the most serious finding there is.
     */
    private ReconciliationSubject loadSubject(String provider, String externalTransactionId) {

        // A payment with no capture posting contributes NO expected view, and is therefore not an
        // internal record at all.
        //
        // This is the fix for a defect that made the whole engine return 500. A payment can
        // legitimately reach a state where the provider names a transaction we hold a payment for
        // but that payment never captured: a `payment.captured` webhook delivered against a FAILED
        // payment writes the provider row (that write happens before the state decision) and then
        // correctly decides NO_EFFECT. The previous code threw IllegalStateException from the
        // currency lookup, which escaped runBatch, killed the whole batch, and orphaned it in
        // RUNNING forever.
        //
        // Dropping it here is the honest answer rather than a suppression. The expected side is
        // derived from the LEDGER (ADR-0005), and a payment with no capture posting has contributed
        // no money to the ledger. There is no internal *money* record to compare against, so the
        // subject classifies as MISSING_INTERNAL — which is exactly the finding an operator needs:
        // the provider says money moved and we have booked none.
        List<ReconciliationSubject.Expected> internal = sql.sql("""
                SELECT p.id, p.merchant_reference, p.state, p.settlement_record_id,
                       p.provider_transaction_id, p.captured_at
                  FROM payments p
                 WHERE p.provider = ? AND p.provider_transaction_id = ?
                """)
                .params(provider, externalTransactionId)
                .list((rs, rowNum) -> expectedView(
                        provider, rs.getString("id"), rs.getString("provider_transaction_id"),
                        rs.getString("merchant_reference"), rs.getString("state"),
                        rs.getString("settlement_record_id")))
                .stream()
                .flatMap(Optional::stream)
                .toList();

        List<ReconciliationSubject.ProviderRecord> providerRecords = sql.sql("""
                SELECT provider_transaction_id, merchant_reference, gross_amount_minor,
                       fee_amount_minor, net_amount_minor, currency, provider_status
                  FROM provider_transactions
                 WHERE provider = ? AND provider_transaction_id = ?
                """)
                .params(provider, externalTransactionId)
                .list((rs, rowNum) -> new ReconciliationSubject.ProviderRecord(
                        Money.of(rs.getLong("gross_amount_minor"), CurrencyCode.parse(rs.getString("currency"))),
                        Money.of(rs.getLong("fee_amount_minor"), CurrencyCode.parse(rs.getString("currency"))),
                        Money.of(rs.getLong("net_amount_minor"), CurrencyCode.parse(rs.getString("currency"))),
                        PaymentState.valueOf(rs.getString("provider_status")),
                        rs.getString("merchant_reference"),
                        settlementDateFor(provider, rs.getString("provider_transaction_id")),
                        settlementValidityFor(provider, rs.getString("provider_transaction_id"))));

        return new ReconciliationSubject(provider, externalTransactionId, internal, providerRecords);
    }

    /**
     * What the ledger says should be true for this payment.
     *
     * <p>Read from the <b>capture transaction's entries by account code</b>, which is why the fee
     * split is a ledger concern rather than a payment-row concern. If the payment row says one fee
     * and the ledger says another, this returns the ledger's figure and the disagreement surfaces
     * as a mismatch rather than being smoothed over.
     *
     * @return empty when the payment has no capture posting. That is a legitimate state, not an
     *         error: nothing has been booked, so there is no expected side to compare against, and
     *         the caller treats the payment as absent from the internal side. Returning empty rather
     *         than throwing is what stops one such payment from failing an entire batch — it used to,
     *         and no test covered it because every subject in the golden fixture had captured.
     */
    private Optional<ReconciliationSubject.Expected> expectedView(
            String provider,
            String paymentId,
            String providerTransactionId,
            String merchantReference,
            String state,
            String settlementRecordId) {

        Optional<String> currency = sql.sql("""
                SELECT currency FROM ledger_transactions
                 WHERE id = (SELECT ledger_transaction_id FROM payment_ledger_transactions
                              WHERE payment_id = ? AND role = 'CAPTURE')
                """)
                .param(paymentId)
                .optional((rs, rowNum) -> rs.getString("currency"));

        if (currency.isEmpty()) {
            // No capture posting. Not captured, or captured and already reversed with no link left —
            // either way the ledger holds no capture for this payment to reconcile against.
            return Optional.empty();
        }

        Map<String, Long> legs = captureLegs(paymentId, "DEBIT");
        Map<String, Long> credits = captureLegs(paymentId, "CREDIT");

        CurrencyCode code = CurrencyCode.parse(currency.get());
        long gross = legs.getOrDefault("PSP_CLEARING", 0L);
        long fee = credits.getOrDefault("PLATFORM_FEE_REVENUE", 0L);
        long net = credits.getOrDefault("MERCHANT_PAYABLE", 0L);

        boolean settled = settlementRecordId != null;
        return Optional.of(new ReconciliationSubject.Expected(
                paymentId,
                Money.of(gross, code),
                Money.of(fee, code),
                Money.of(net, code),
                settled ? PaymentState.SETTLED : PaymentState.CAPTURED,
                merchantReference,
                settled ? settlementDateFor(settlementRecordId) : null));
    }

    /**
     * The capture posting's legs, totalled per account code and split by direction.
     *
     * <p>Reading by account code rather than by position is what makes this robust: it does not care
     * whether a transaction happens to post three entries or five, and it cannot silently pick up
     * the wrong leg if the posting's line order ever changes.
     */
    private Map<String, Long> captureLegs(String paymentId, String direction) {
        Map<String, Long> totals = new java.util.LinkedHashMap<>();
        for (String[] row : sql.sql("""
                SELECT e.account_code, SUM(e.amount_minor) AS total
                  FROM payment_ledger_transactions plt
                  JOIN ledger_entries e ON e.transaction_id = plt.ledger_transaction_id
                 WHERE plt.payment_id = ? AND plt.role = 'CAPTURE'
                   AND e.direction = ?
                 GROUP BY e.account_code
                """)
                .params(paymentId, direction)
                .list((rs, rowNum) ->
                        new String[] {rs.getString("account_code"), Long.toString(rs.getLong("total"))})) {

            totals.merge(row[0], Long.parseLong(row[1]), Long::sum);
        }
        return totals;
    }

    private LocalDate settlementDateFor(String settlementRecordId) {
        return sql.sql("SELECT settlement_date FROM settlement_records WHERE id = ?")
                .param(settlementRecordId)
                .optional((rs, rowNum) -> rs.getObject("settlement_date", LocalDate.class))
                .orElse(null);
    }

    private LocalDate settlementDateFor(String provider, String providerTransactionId) {
        // Ordered, because two settlement records can legitimately cover one transaction - a
        // provider that re-issues a corrected settlement writes a second row - and "whichever the
        // planner returned first" is not a date, it is a coin toss. Latest wins, because that is
        // what the provider's most recent report says.
        return sql.sql("""
                SELECT r.settlement_date
                  FROM settlement_record_lines l
                  JOIN settlement_records r ON r.id = l.settlement_record_id
                 WHERE r.provider = ? AND l.provider_transaction_id = ?
                 ORDER BY r.settlement_date DESC, r.id DESC
                 LIMIT 1
                """)
                .params(provider, providerTransactionId)
                .optional((rs, rowNum) -> rs.getObject("settlement_date", LocalDate.class))
                .orElse(null);
    }

    /**
     * Whether the settlement record covering a transaction was accepted.
     *
     * <p>Resolved with the same "latest record wins" rule as {@link #settlementDateFor} and, more
     * importantly, from the same <i>record</i>: a provider that re-issues a corrected settlement
     * must be able to clear a finding its own broken first attempt raised, and pairing a date from
     * one record with a validity from another would invent a verdict nobody sent.
     *
     * <p>Returns {@code null} when no settlement covers the transaction, which the classifier reads
     * as "not covered by a broken settlement" rather than as an absence of evidence.
     */
    private SettlementValidity settlementValidityFor(String provider, String providerTransactionId) {
        return sql.sql("""
                SELECT r.validity
                  FROM settlement_record_lines l
                  JOIN settlement_records r ON r.id = l.settlement_record_id
                 WHERE r.provider = ? AND l.provider_transaction_id = ?
                 ORDER BY r.settlement_date DESC, r.id DESC
                 LIMIT 1
                """)
                .params(provider, providerTransactionId)
                .optional((rs, rowNum) -> SettlementValidity.valueOf(rs.getString("validity")))
                .orElse(null);
    }

    // ------------------------------------------------------------------ helpers

    private static String internalPaymentId(ReconciliationSubject subject) {
        return subject.internal().isEmpty() ? null : subject.internal().get(0).paymentId();
    }

    private static Long expectedAmount(ReconciliationSubject subject, boolean fee) {
        return subject.internal().isEmpty()
                ? null
                : (fee ? subject.internal().get(0).fee().amountMinor()
                        : subject.internal().get(0).net().amountMinor());
    }

    private static String expectedCurrency(ReconciliationSubject subject) {
        return subject.internal().isEmpty()
                ? null
                : subject.internal().get(0).gross().currency().code();
    }

    private static Long providerAmount(ReconciliationSubject subject, boolean fee) {
        return subject.providerRecords().isEmpty()
                ? null
                : (fee ? subject.providerRecords().get(0).fee().amountMinor()
                        : subject.providerRecords().get(0).net().amountMinor());
    }

    private static String actualCurrency(ReconciliationSubject subject) {
        return subject.providerRecords().isEmpty()
                ? null
                : subject.providerRecords().get(0).gross().currency().code();
    }

    private static String actualStatus(ReconciliationSubject subject) {
        return subject.providerRecords().isEmpty()
                ? null
                : subject.providerRecords().get(0).status().name();
    }

    /**
     * Every difference, serialised.
     *
     * <p>All of them, not just the primary reason: an operator who is told the settlement is 3.00
     * short and a day late should see both in one place, rather than finding the second problem
     * after fixing the first.
     *
     * <p>This used to build the JSON by hand with a StringBuilder, and that was a defect reachable
     * from a provider. Found by {@code audit-probe/vv-remediation.mjs} against a running container,
     * not by the suite.
     *
     * <ol>
     *   <li><b>Control characters were not escaped.</b> RFC 8259 forbids a raw newline, tab or any
     *       other character below U+0020 inside a JSON string, and the old {@code quote()} escaped
     *       only backslash and double quote. The values reaching here include the merchant
     *       reference on both sides of a REFERENCE_MISMATCH, which the provider supplies. One
     *       newline in one reference made the INSERT fail with "invalid input syntax for type
     *       json", and {@code ReconciliationWorker} then marked the WHOLE batch FAILED - so a single
     *       provider string with a newline in it stopped every payment in the window being
     *       reconciled. The engine stopped working precisely when it had something to report.</li>
     *   <li><b>Hand-rolled escaping is a JSON-injection primitive.</b> Any provider-controlled value
     *       in this document could close a string and append structure of its own, corrupting an
     *       audit column that operators read as a faithful record of what disagreed.</li>
     * </ol>
     *
     * <p>A real serialiser has neither problem and cannot regress on either: it is the definition of
     * the format rather than a re-derivation of it. The keys, their order and the null handling are
     * unchanged, so this replaces the mechanism and not the contract.
     */
    private static String differencesJson(ReconciliationClassification classification) {
        List<Map<String, Object>> document = new ArrayList<>();
        for (ReconciliationClassification.Difference difference : classification.differences()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("reason", difference.component().name());
            entry.put("attribute", difference.attribute());
            entry.put("expected", difference.expected());
            entry.put("actual", difference.actual());
            entry.put("deltaMinor", difference.deltaMinor());
            document.add(entry);
        }
        try {
            return JSON.writeValueAsString(document);
        } catch (JsonProcessingException unserialisable) {
            // Unreachable for a List<Map<String,Object>> of scalars and Longs, which is the whole
            // point of handing this to a real serialiser rather than to string concatenation. It is
            // still handled, because a batch is exactly where an unchecked exception takes down
            // every payment in the window rather than the one that caused it.
            throw new IllegalStateException(
                    "differences could not be serialised for subject " + classification.subjectKey(), unserialisable);
        }
    }

    /**
     * One subject in the frozen population.
     *
     * @param side which side supplied it to the snapshot — {@code BOTH} when the provider and the
     *             ledger agree the transaction exists, {@code PROVIDER} when only the provider
     *             reported it. Recorded for diagnostics; the comparison itself always considers
     *             both sides, because a subject only the ledger knows about is a finding, not an
     *             absence.
     */
    private record SubjectRow(String externalTransactionId, String side) {

        /** {@code provider:externalId}, matching the key the classifier derives for itself. */
        String subjectKey(String provider) {
            return provider + ":" + externalTransactionId;
        }
    }
}