package com.reconcile.service;

import com.reconcile.payment.PaymentState;
import com.reconcile.payment.PaymentStateMachine;
import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.provider.ProviderEventPayload;
import com.reconcile.reconciliation.SettlementRecordValidator;
import com.reconcile.shared.CurrencyCode;
import com.reconcile.shared.Money;
import com.reconcile.shared.Ulid;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Applies the effect of one provider event, in its own transaction.
 *
 * <p>Asynchronous by design ({@code docs/spec/03 §B4}): the webhook handler persists and returns, so
 * the event is durable before any money moves. That is what makes a crash between the two
 * recoverable — the worker re-claims the row and applies the effect exactly once.
 *
 * <p>The interesting decision is {@link #applyPaymentEvent}'s handling of an event that does not fit
 * the current state. Spec §B5 gives three cases and all three are deliberate; see
 * {@link PaymentEventDecision} for why neither of the obvious alternatives is acceptable.
 */
@Service
public class ProviderEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(ProviderEventProcessor.class);

    private final Sql sql;
    private final PaymentService payments;
    private final LedgerService ledger;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public ProviderEventProcessor(
            Sql sql,
            PaymentService payments,
            LedgerService ledger,
            AuditService audit,
            ObjectMapper mapper) {

        this.sql = sql;
        this.payments = payments;
        this.ledger = ledger;
        this.audit = audit;
        this.mapper = mapper;
    }

    /** What applying one event did. Drives the event's terminal status. */
    public enum Effect {
        PROCESSED,
        NO_EFFECT,
        FAILED
    }

    /** An event whose requested transition does not fit the payment's current state. */
    enum PaymentEventDecision {
        /** Legal from here: apply it. */
        APPLY,
        /** Superseded: the payment is already at least as far along, so this carries no new news. */
        NO_EFFECT,
        /** Our record is behind the provider's: apply it anyway and flag it. */
        APPLY_OUT_OF_ORDER
    }

    /**
     * Applies one event. The caller owns the transaction; this method must not open its own, or a
     * rollback would leave the event marked processed with no effect applied.
     */
    @Transactional
    public Effect apply(String provider, String eventId, String payloadJson, String requestId) {

        ProviderEventPayload payload;
        try {
            payload = mapper.readValue(payloadJson, ProviderEventPayload.class);
        } catch (RuntimeException malformed) {
            // A body that cannot be parsed now but was stored will never parse: the signature was
            // verified against these exact bytes, so this is not a transient fault and must not be
            // retried against a backoff.
            throw new NonRetryableEventException("stored payload no longer parses: " + malformed.getMessage());
        }

        if ("settlement.paid".equals(payload.type())) {
            return applySettlement(provider, payload, requestId);
        }
        return applyPaymentEvent(provider, payload, requestId);
    }

    // ------------------------------------------------------------------ payments

    private Effect applyPaymentEvent(
            String provider, ProviderEventPayload payload, String requestId) {

        if (payload.providerTransactionId() == null) {
            throw new NonRetryableEventException("event type " + payload.type() + " carries no providerTransactionId");
        }

        // Validated before anything is written. An unrecognised event type must never reach
        // provider_transactions, and must never be reported as a retryable "no such payment"
        // failure that looks like a data problem rather than a contract mismatch.
        PaymentState target = targetStateFor(payload);

        // The provider's view of the transaction is recorded whether or not we have a payment for it.
        // Reconciliation needs the provider's row to exist in order to report what it disagrees with.
        upsertProviderTransaction(provider, payload);

        String paymentId = sql.sql(
                "SELECT id FROM payments WHERE provider_transaction_id = ? AND provider = ?")
                .params(payload.providerTransactionId(), provider)
                .optional((rs, rowNum) -> rs.getString("id"))
                .orElseThrow(() -> new RetryableEventException(
                        "no payment carries provider transaction " + payload.providerTransactionId()));

        PaymentState current = sql.sql("SELECT state FROM payments WHERE id = ?")
                .param(paymentId)
                .optional((rs, rowNum) -> PaymentState.valueOf(rs.getString("state")))
                .orElseThrow();

        PaymentEventDecision decision = decide(current, target);

        if (decision == PaymentEventDecision.NO_EFFECT) {
            audit.record("WEBHOOK", provider, "WEBHOOK_NO_EFFECT", "PAYMENT", paymentId, requestId,
                    Map.of("eventType", payload.type(),
                            "currentState", current.name(),
                            "requestedState", target.name()));
            return Effect.NO_EFFECT;
        }

        PaymentService.CommandContext context = PaymentService.CommandContext.of(
                new PaymentService.Actor("WEBHOOK", provider), requestId, null);

        // Defect 22, third finding. Spec 03 §B5's third row says an event the payment is behind must
        // be applied anyway and flagged, and for a capture this is exact rather than generous:
        // AUTHORIZED moves no money, so stepping through it produces precisely the state the
        // capture would have produced had the authorisation arrived first. Only transitions that
        // move no money are walked — anything else would be inventing a posting the provider did
        // not tell us about. Before this, a capture that arrived first threw
        // IllegalStateTransitionException, burned eight retries and died, stranding the capture
        // and leaving the payment stuck at CREATED forever.
        if (decision == PaymentEventDecision.APPLY_OUT_OF_ORDER
                && current == PaymentState.CREATED
                && target == PaymentState.CAPTURED) {
            payments.authorize(paymentId, context);
        }

        switch (target) {
            case AUTHORIZED -> payments.authorize(paymentId, context);
            case CAPTURED -> payments.capture(paymentId, null, context);
            case FAILED -> payments.fail(paymentId, "PROVIDER_DECLINED",
                    "provider reported failure", context);
            case REFUNDED -> payments.refund(paymentId, context);
            case SETTLED -> {
                // Settlements arrive as their own event type with the record attached; a bare
                // payment.settled cannot name the record, and markSettled refuses a null one
                // because a settled payment without a settlement record cannot be reconciled.
                throw new NonRetryableEventException(
                        "payment.settled must arrive as settlement.paid carrying its record");
            }
            default -> throw new NonRetryableEventException(
                    "event type " + payload.type() + " has no effect");
        }

        if (decision == PaymentEventDecision.APPLY_OUT_OF_ORDER) {
            audit.record("WEBHOOK", provider, "WEBHOOK_OUT_OF_ORDER", "PAYMENT", paymentId,
                    requestId, Map.of("eventType", payload.type(),
                            "fromState", current.name(), "toState", target.name()));
        }
        return Effect.PROCESSED;
    }

    /**
     * §B5's three-way decision.
     *
     * <p>Refusing an out-of-order event would leave our record permanently behind the provider's, and
     * the reconciliation case that follows would then have no honest explanation. Applying an event
     * the payment has already superseded would resurrect a state it has legitimately left. So: apply
     * when legal, ignore when superseded, and apply-and-flag when we are the ones behind — choosing
     * to lie in neither direction.
     */
    private static PaymentEventDecision decide(PaymentState current, PaymentState target) {
        if (PaymentStateMachine.isAllowed(current, target)) {
            return PaymentEventDecision.APPLY;
        }
        if (current.isAtLeastAsFarAs(target)) {
            return PaymentEventDecision.NO_EFFECT;
        }
        return PaymentEventDecision.APPLY_OUT_OF_ORDER;
    }

    private static PaymentState targetStateFor(ProviderEventPayload payload) {
        return switch (payload.type()) {
            case "payment.created" -> PaymentState.CREATED;
            case "payment.authorized" -> PaymentState.AUTHORIZED;
            case "payment.captured" -> PaymentState.CAPTURED;
            case "payment.settled" -> PaymentState.SETTLED;
            case "payment.refunded" -> PaymentState.REFUNDED;
            case "payment.failed" -> PaymentState.FAILED;
            default -> throw new NonRetryableEventException(
                    "unknown event type " + payload.type());
        };
    }

    // --------------------------------------------------------------- settlement

    /**
     * Ingests a settlement record and settles every payment it covers.
     *
     * <p>Idempotent on {@code (provider, provider_settlement_id)}: a provider that sends the same
     * settlement twice, or a push racing a pull, must not produce two records — and re-settling a
     * payment would break L10, which is why {@code markSettled} returns early rather than
     * re-applying.
     */
    private Effect applySettlement(
            String provider, ProviderEventPayload payload, String requestId) {

        ProviderEventPayload.Settlement settlement = payload.settlement();
        if (settlement == null) {
            throw new NonRetryableEventException("settlement.paid carries no settlement object");
        }

        // Judged before anything is written, and judged once. Everything below either honours this
        // record or preserves it; nothing below is allowed to decide for itself whether the record
        // is coherent, because "settle it anyway" and "drop it" were both the previous behaviour and
        // they were both wrong in different directions.
        SettlementRecordValidator.Verdict verdict = SettlementRecordValidator.validate(
                settlement, transactionId -> currencyOfPaymentFor(provider, transactionId));

        String recordId = existingSettlementId(provider, settlement.providerSettlementId());
        if (recordId == null) {
            recordId = insertSettlement(provider, settlement, verdict);
        }
        linkLines(recordId, settlement);

        if (!verdict.valid()) {
            return refuseInvalidSettlement(provider, recordId, settlement, verdict, requestId);
        }

        PaymentService.CommandContext context = PaymentService.CommandContext.of(
                new PaymentService.Actor("WEBHOOK", provider), requestId, null);

        int[] settled = {0};
        // Our own gross, not the provider's line figure. PSP_CLEARING was credited at capture with
        // our number; posting the provider's would either drive clearing negative or quietly leave
        // residue in it. Either way the difference would be normalised away here instead of being
        // reported by reconciliation, which is the one job this engine has.
        long[] settledGrossMinor = {0L};
        String settlementId = recordId;
        for (ProviderEventPayload.Line line : settlement.lines()) {
            // The provider's statement of this transaction must exist before it can be compared.
            //
            // A settlement *is* the provider telling us about a transaction, so when the capture
            // event was lost the row is written from facts we already hold rather than waited for:
            // the line's own gross and net, and our payment's captured_at and merchant_reference.
            // captured_at comes from our own capture - the same instant PSP_CLEARING was credited -
            // so it is a fact, not a reconstruction, and it places the transaction in the window it
            // actually belongs to. Leaving the row absent instead makes reconciliation report
            // MISSING_ON_PROVIDER against a transaction the provider has demonstrably settled,
            // which is a false discrepancy manufactured by our own ingestion gap.
            //
            // ON CONFLICT DO NOTHING, so a row written by the capture event is never overwritten
            // with the settlement line's slightly coarser figures: the richer statement wins.
            //
            // `payment_id` is part of the column list, not an afterthought. Without it the row a
            // settlement creates has no link back to the payment it settles, and that link is what
            // reconciliation joins on.
            recordProviderClaimForLine(provider, line, settlement);

            // Resolve the payment. Settling a line whose payment we do not have would mark a
            // provider transaction settled against nothing.
            String paymentId = sql.sql(
                    "SELECT id FROM payments WHERE provider_transaction_id = ? AND provider = ?")
                    .params(line.providerTransactionId(), provider)
                    .optional((rs, rowNum) -> rs.getString("id"))
                    .orElse(null);

            if (paymentId == null) {
                // No payment for this line. The provider's row was still written above, and that is
                // the point: dropping it here is what made such transactions invisible to
                // reconciliation forever, because the subject population is built from `payments`
                // and `provider_transactions` and a settlement-only transaction was in neither, so
                // no batch could ever raise it. Reconciliation now reports it MISSING_INTERNAL,
                // which is the true finding.
                continue;
            }

            // Defect 22. Where the settle actually stands is decided by markSettled under the payment's row
            // lock, further down, and interpreted where it refuses — not guessed at here. A check
            // before the lock is a check that can be wrong: two workers applying a refund and a
            // settlement at the same time read the same state and only one of them is right.

            // The provider-side status is updated to match what we just did to the payment.
            //
            // Before this change the row was unconditionally set to SETTLED whenever a settlement
            // covered the line, even when the payment had moved past SETTLED in the meantime (for
            // example REFUNDED after settlement). That is defect 21/22's family: our provider-side
            // row was being driven by the settlement's arrival order rather than by the payment's
            // actual state, so the provider's own statement could be dragged backwards.
            //
            // Now the row follows the payment: if we just settled it, this line is SETTLED; if the
            // payment is already further along, the row keeps its later state. The monotonic rank
            // that prevents an earlier event from overwriting a later one is enforced once, in
            // upsertProviderTransaction — this is not the place to re-derive it.

            // Only a payment this call actually settled counts. A second delivery of the same
            // settlement finds the payment already SETTLED, and counting it would post the
            // settlement a second time.
            //
            // Defect 22. The outcome is decided under the payment's row lock rather than by a
            // check-then-act pair, because a check-then-act has a window: a refund applied by
            // another worker between the two turns this into a transaction that is already marked
            // rollback-only by the time the caller reacts. A payment that is refunded (or already
            // settled) has heard this news, so the record and its lines are kept and nothing is
            // applied. A payment that is merely not captured yet is blocked — its capture event is
            // still in flight — so the whole event is rolled back and re-queued rather than burning
            // the retry budget that exists for faults. Before this, a settlement that overtook its
            // own capture spent eight attempts and five minutes on being early, and then died
            // holding money the provider had really paid.
            PaymentService.SettleOutcome outcome = payments.settleIfPossible(paymentId, settlementId,
                    settlement.settlementDate().toString(),
                    PaymentStateMachine.Trigger.SETTLEMENT, context);

            if (outcome == PaymentService.SettleOutcome.NOT_CAPTURED) {
                throw new BlockedEventException(
                        "settlement " + settlement.providerSettlementId() + " covers " + paymentId
                                + ", which is not captured yet");
            }
            if (outcome != PaymentService.SettleOutcome.SETTLED_NOW) {
                // The payment was not settled by this call, or is already beyond settlement. The
                // provider-side row should not be forced to SETTLED by this settlement's arrival.
                // If the row is still at CAPTURED or absent, the provider has not reported anything
                // later, so settle it here; otherwise leave it alone.
                updateProviderStatusIfNotAlreadyPastCaptured(provider, line.providerTransactionId());
                continue;
            }

            updateProviderStatusToSettled(paymentId, provider, line.providerTransactionId());
            settledGrossMinor[0] += capturedGrossOf(paymentId);
            settled[0]++;
        }

        // The cash movement, and it is here rather than inside markSettled on purpose: a
        // settlement is one business fact worth one posting, however many lines it covers, and
        // payments.markSettled is also called for a single payment by paths that must not each
        // invent a settlement of their own.
        //
        // Posted only when this delivery actually settled something. A re-delivery settles nothing,
        // so it posts nothing - which is what makes push/pull agreement hold even when the two
        // race. The (SETTLEMENT_RECORD, recordId) unique constraint makes a second posting
        // impossible regardless.
        if (settled[0] > 0) {
            ledger.post(
                    Postings.settlementReceived(
                            settlementId,
                            Money.of(settledGrossMinor[0], CurrencyCode.parse(settlement.currency())),
                            provider),
                    context.posting());
        }

        audit.record("WEBHOOK", provider, "SETTLEMENT_INGESTED", "SETTLEMENT_RECORD", settlementId,
                requestId, Map.of("lines", settlement.lines().size(), "settled", settled[0],
                        "grossMinor", settledGrossMinor[0]));

        // A settlement may cover a transaction we have no payment for at all. Nothing is invented here:
        // the line is stored with a null provider_transaction_ref, and the transaction stays out of
        // every reconciliation window, because the subject population is built from `payments` and
        // `provider_transactions` and a settlement-only transaction is in neither. That gap is
        // documented in the README rather than papered over here - inventing a payment or a capture
        // time for it would be worse than the silence, because it would put a fabricated row into
        // the one place reconciliation trusts to be the provider's own word.
        return Effect.PROCESSED;
    }

    /** The gross we captured for a payment: what actually sits in {@code PSP_CLEARING} for it. */
    private long capturedGrossOf(String paymentId) {
        return sql.sql("SELECT amount_minor FROM payments WHERE id = ?")
                .param(paymentId)
                .optional((rs, rowNum) -> rs.getLong("amount_minor"))
                .orElseThrow(() -> new RetryableEventException(
                        "payment " + paymentId + " disappeared while settling"));
    }

    /**
     * After this call settles a payment, promote the provider-side row to SETTLED.
     *
     * <p>The guard is a <b>rank comparison</b>, not a test for one particular status. An earlier
     * version promoted only when the row was at CAPTURED or NULL, which was wrong for a reason that
     * only an out-of-order delivery can produce: {@link #upsertProviderTransaction} writes the
     * provider row <i>before</i> the payment-side decision is taken, so a late {@code payment.created}
     * or {@code payment.authorized} leaves the row at CREATED or AUTHORIZED even though the payment
     * side correctly decided NO_EFFECT. A settlement arriving in that window then matched no rows,
     * was silently dropped, and a later capture event promoted the row to CAPTURED - leaving a
     * payment that is genuinely SETTLED, with its money posted, permanently disagreeing with the
     * provider row. The next reconciliation batch then reported STATUS_MISMATCH against a correct
     * payment, which is the one direction this engine must never be wrong in.
     *
     * <p>So the row is promoted whenever it ranks <i>below</i> SETTLED, and never regressed when it
     * ranks above it (FAILED and REFUNDED genuinely supersede a settlement). This is the same rank
     * rule {@link #upsertProviderTransaction} applies, so the forward write and the conflict clause
     * cannot disagree.
     */
    private void updateProviderStatusToSettled(String paymentId, String provider, String providerTransactionId) {
        sql.sql("""
                UPDATE provider_transactions
                   SET provider_status = 'SETTLED'
                 WHERE provider = ? AND provider_transaction_id = ?
                   AND (provider_status IS NULL
                        OR (CASE provider_status
                                WHEN 'CREATED' THEN 1 WHEN 'AUTHORIZED' THEN 2
                                WHEN 'CAPTURED' THEN 3 WHEN 'SETTLED' THEN 4
                                WHEN 'FAILED' THEN 5 WHEN 'REFUNDED' THEN 6 END) < 4)
                """)
                .params(provider, providerTransactionId)
                .update();
    }

    /**
     * When a settlement covers a payment that was not settled by this call, promote the provider-side
     * row only if it still ranks below SETTLED. The row is the provider's statement of the
     * transaction, not our payment state, so we do not copy our payment state into it; we only avoid
     * forcing it backwards to SETTLED when the provider has in fact reported a later status.
     */
    private void updateProviderStatusIfNotAlreadyPastCaptured(String provider, String providerTransactionId) {
        // No-op unless the row ranks below SETTLED. The monotonic 'later event wins' rule is enforced
        // once, in upsertProviderTransaction, so this method never writes a status earlier than what
        // the provider has already reported - and, for the same reason as above, it must promote from
        // CREATED and AUTHORIZED too, not only from CAPTURED: an out-of-order event can legitimately
        // leave the row at an earlier status while the payment side has moved on.
        sql.sql("""
                UPDATE provider_transactions
                   SET provider_status = 'SETTLED'
                 WHERE provider = ? AND provider_transaction_id = ?
                   AND (provider_status IS NULL
                        OR (CASE provider_status
                                WHEN 'CREATED' THEN 1 WHEN 'AUTHORIZED' THEN 2
                                WHEN 'CAPTURED' THEN 3 WHEN 'SETTLED' THEN 4
                                WHEN 'FAILED' THEN 5 WHEN 'REFUNDED' THEN 6 END) < 4)
                   AND NOT EXISTS (
                       SELECT 1 FROM payments p
                         WHERE p.id = (SELECT payment_id FROM provider_transactions
                                        WHERE provider = ? AND provider_transaction_id = ?)
                           AND p.state IS NOT DISTINCT FROM 'SETTLED'
                   )
                """)
                .params(provider, providerTransactionId, provider, providerTransactionId)
                .update();
    }

    /**
     * The currency of the payment carrying a provider transaction id, or empty when we have none.
     *
     * <p>Deliberately the <i>payment's</i> currency and never the record's. Reading the record's
     * currency here would make the settlement currency agree with itself by construction, which is
     * the exact circularity this whole validation exists to break.
     */
    private Optional<String> currencyOfPaymentFor(String provider, String providerTransactionId) {
        return sql.sql("""
                SELECT currency
                  FROM payments
                 WHERE provider = ? AND provider_transaction_id = ?
                """)
                .params(provider, providerTransactionId)
                .optional((rs, rowNum) -> rs.getString("currency"));
    }

    /**
     * Keeps an unusable settlement record without letting it move money.
     *
     * <p>The record and its lines are already stored by the time this runs — that is deliberate,
     * because a provider whose statement is broken is exactly the case an operator needs to be able
     * to look at. What must not happen is any of the three things that made this a HIGH finding:
     * settling the payments, posting cash, or leaving the outcome invisible.
     *
     * <p>So the record is marked {@code INVALID} with its reason, the provider's own claim for each
     * covered transaction is persisted (which is what makes reconciliation able to see the
     * disagreement and say so), an audit event records the refusal, and the event terminates
     * normally. It is <i>not</i> a retryable failure: nothing about a second attempt would produce a
     * different answer, and burning the retry budget on a deterministic outcome is how poison events
     * crowd out the ones that are genuinely waiting on the world.
     */
    private Effect refuseInvalidSettlement(
            String provider,
            String recordId,
            ProviderEventPayload.Settlement settlement,
            SettlementRecordValidator.Verdict verdict,
            String requestId) {

        // The provider's claim is recorded exactly as sent, in the provider's currency.
        //
        // This is the other half of the cross-currency fix. The provider-side row used to be
        // written with the *payment's* currency, which silently replaced the provider's claim with
        // ours — and then reconciliation compared the payment against that substituted value,
        // agreed with itself, and reported MATCHED for a settlement that had just posted USD
        // ledger entries against an EGP-cleared receivable. Recording what the provider actually
        // said is what makes the comparison mean something.
        for (ProviderEventPayload.Line line : settlement.lines()) {
            recordProviderClaimForLine(provider, line, settlement);
        }

        audit.record("WEBHOOK", provider, "SETTLEMENT_REJECTED_INVALID", "SETTLEMENT_RECORD",
                recordId, requestId,
                Map.of("providerSettlementId", settlement.providerSettlementId(),
                        "currency", settlement.currency(),
                        "lines", settlement.lines().size(),
                        "reason", verdict.reason()));

        log.warn("settlement {} from {} is invalid and was not applied: {}",
                settlement.providerSettlementId(), provider, verdict.reason());

        return Effect.PROCESSED;
    }

    /**
     * Persists the provider's statement of one transaction, denominated as the provider stated it.
     *
     * <p>Shared by the valid and invalid paths, which is the point: the provider's claim is the
     * provider's claim regardless of whether we are prepared to act on it. Reconciliation needs the
     * row either way — it is the only place the provider's own figures exist.
     */
    private void recordProviderClaimForLine(
            String provider, ProviderEventPayload.Line line, ProviderEventPayload.Settlement settlement) {

        long lineFee = line.grossAmountMinor() - line.netAmountMinor();

        sql.sql("""
                INSERT INTO provider_transactions
                    (id, provider, provider_transaction_id, payment_id, merchant_reference,
                     gross_amount_minor, fee_amount_minor, net_amount_minor, currency,
                     provider_status, source, captured_at)
                SELECT ?, ?, p.provider_transaction_id, p.id, p.merchant_reference,
                       ?, ?, ?, ?, 'SETTLED', 'WEBHOOK', p.captured_at
                  FROM payments p
                 WHERE p.id = (SELECT id FROM payments
                                WHERE provider = ? AND provider_transaction_id = ?)
                ON CONFLICT (provider, provider_transaction_id) DO NOTHING
                """)
                .params(Ulid.of("psptxn"), provider,
                        line.grossAmountMinor(), lineFee, line.netAmountMinor(),
                        settlement.currency(),
                        provider, line.providerTransactionId())
                .update();

        // A line whose payment we do not have is still the provider telling us the transaction
        // exists. It is stored provider-side with a NULL captured_at because we genuinely do not
        // know when it was captured: a settlement line carries no capture time, and inventing one
        // from the settlement date would put a fabricated timestamp into the one column that
        // decides which reconciliation window a transaction belongs to.
        sql.sql("""
                INSERT INTO provider_transactions
                    (id, provider, provider_transaction_id, merchant_reference,
                     gross_amount_minor, fee_amount_minor, net_amount_minor, currency,
                     provider_status, source, captured_at)
                VALUES (?, ?, ?, 'UNKNOWN', ?, ?, ?, ?, 'SETTLED', 'WEBHOOK', NULL)
                ON CONFLICT (provider, provider_transaction_id) DO NOTHING
                """)
                .params(Ulid.of("psptxn"), provider, line.providerTransactionId(),
                        line.grossAmountMinor(), lineFee, line.netAmountMinor(),
                        settlement.currency())
                .update();
    }

    private String existingSettlementId(String provider, String providerSettlementId) {
        return sql.sql(
                "SELECT id FROM settlement_records WHERE provider = ? AND provider_settlement_id = ?")
                .params(provider, providerSettlementId)
                .optional((rs, rowNum) -> rs.getString("id"))
                .orElse(null);
    }

    /**
     * Stores the provider's record exactly as sent, with the verdict attached.
     *
     * <p>The figures are never adjusted. An earlier version compared the record's gross with the
     * sum of its lines and threw a non-retryable exception on a mismatch, which destroyed the
     * document on the grounds that it was inconsistent — the one thing an operator most needs to be
     * able to read back. Now the record is stored verbatim and marked, and the arithmetic lives in
     * {@link SettlementRecordValidator} where it can be tested without a database.
     */
    private String insertSettlement(
            String provider,
            ProviderEventPayload.Settlement settlement,
            SettlementRecordValidator.Verdict verdict) {

        String recordId = Ulid.of("srec");

        sql.sql("""
                INSERT INTO settlement_records
                    (id, provider, provider_settlement_id, gross_amount_minor, fee_amount_minor,
                     net_amount_minor, currency, settlement_date, source, validity, validity_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'WEBHOOK', ?, ?)
                ON CONFLICT (provider, provider_settlement_id) DO NOTHING
                """)
                .params(recordId, provider, settlement.providerSettlementId(),
                        settlement.grossAmountMinor(), settlement.feeAmountMinor(),
                        settlement.netAmountMinor(), settlement.currency(),
                        settlement.settlementDate(),
                        verdict.validity().name(),
                        truncate(verdict.reason()))
                .update();

        return sql.sql(
                "SELECT id FROM settlement_records WHERE provider = ? AND provider_settlement_id = ?")
                .params(provider, settlement.providerSettlementId())
                .optional((rs, rowNum) -> rs.getString("id"))
                .orElseThrow();
    }

    /** {@code validity_reason} is {@code varchar(500)}; a longer diagnosis must not lose the row. */
    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 500 ? reason : reason.substring(0, 497) + "...";
    }

    private void linkLines(String recordId, ProviderEventPayload.Settlement settlement) {
        short lineNo = 0;
        for (ProviderEventPayload.Line line : settlement.lines()) {
            sql.sql("""
                    INSERT INTO settlement_record_lines
                        (id, settlement_record_id, provider_transaction_id,
                         provider_transaction_ref, line_gross_minor, line_net_minor, line_no)
                    VALUES (?, ?, ?,
                            (SELECT id FROM provider_transactions
                              WHERE provider_transaction_id = ?), ?, ?, ?)
                    ON CONFLICT (settlement_record_id, line_no) DO NOTHING
                    """)
                    .params(Ulid.of("srline"), recordId, line.providerTransactionId(),
                            line.providerTransactionId(), line.grossAmountMinor(),
                            line.netAmountMinor(), lineNo++)
                    .update();
        }
    }

    // ------------------------------------------------------- provider rows

    /**
     * Records the provider's view of a transaction.
     *
     * <p>An upsert, because this row is <i>their</i> statement of the world, not ours: when a
     * provider corrects a figure, the latest statement is the one reconciliation must compare
     * against. Our own view lives in the ledger, which is untouched here — that separation is what
     * makes agreement between the two sides mean anything (ADR-0005).
     */
    private void upsertProviderTransaction(String provider, ProviderEventPayload payload) {
        if (payload.gross() == null || payload.net() == null) {
            return;
        }
        long fee = payload.fee() == null
                ? payload.gross().amountMinor() - payload.net().amountMinor()
                : payload.fee().amountMinor();
        PaymentState status = payload.status() == null
                ? PaymentState.CAPTURED
                : PaymentState.valueOf(payload.status());

        sql.sql("""
                INSERT INTO provider_transactions
                    (id, provider, provider_transaction_id, merchant_reference, gross_amount_minor,
                     fee_amount_minor, net_amount_minor, currency, provider_status, source, captured_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'WEBHOOK', ?)
                ON CONFLICT (provider, provider_transaction_id) DO UPDATE
                    SET merchant_reference = EXCLUDED.merchant_reference,
                        gross_amount_minor = EXCLUDED.gross_amount_minor,
                        fee_amount_minor    = EXCLUDED.fee_amount_minor,
                        net_amount_minor    = EXCLUDED.net_amount_minor,
                        currency            = EXCLUDED.currency,
                        -- Defect 21. The provider's statement of this transaction is monotonic.
                        --
                        -- Events are applied by a worker, so they are not applied in the order the
                        -- provider emitted them: a batch claiming several events can be processed
                        -- with the settlement before the capture that preceded it. Before this
                        -- clause, the late capture overwrote SETTLED with CAPTURED and reconciliation
                        -- reported STATUS_MISMATCH against a payment that was correctly settled —
                        -- a false discrepancy manufactured by our own ingestion order, which is
                        -- exactly what reconciliation exists to prevent.
                        --
                        -- The ranks mirror PaymentState.isAtLeastAsFarAs, the rule the payment-side
                        -- state machine already applies to the same out-of-order delivery (I-WEB-06).
                        -- The provider-side row was the one write that did not honour it. A genuinely
                        -- later event still wins: REFUNDED and FAILED rank above SETTLED.
                        provider_status     = CASE
                            WHEN (CASE provider_transactions.provider_status
                                    WHEN 'CREATED' THEN 1 WHEN 'AUTHORIZED' THEN 2
                                    WHEN 'CAPTURED' THEN 3 WHEN 'SETTLED' THEN 4
                                    WHEN 'FAILED' THEN 5 WHEN 'REFUNDED' THEN 6 END)
                               > (CASE EXCLUDED.provider_status
                                    WHEN 'CREATED' THEN 1 WHEN 'AUTHORIZED' THEN 2
                                    WHEN 'CAPTURED' THEN 3 WHEN 'SETTLED' THEN 4
                                    WHEN 'FAILED' THEN 5 WHEN 'REFUNDED' THEN 6 END)
                            THEN provider_transactions.provider_status
                            ELSE EXCLUDED.provider_status
                        END
                """)
                .params(Ulid.of("psptxn"), provider, payload.providerTransactionId(),
                        safe(payload.merchantReference()), payload.gross().amountMinor(), fee,
                        payload.net().amountMinor(), payload.gross().currency(), status.name(),
                        payload.occurredAt())
                .update();
    }

    private static String safe(String value) {
        return value == null ? "UNKNOWN" : value;
    }

    // -------------------------------------------------------------- failures

    /**
     * A fault worth retrying: the world may change (the payment may appear, a lock may clear).
     */
    public static class RetryableEventException extends RuntimeException {
        public RetryableEventException(String message) {
            super(message);
        }
    }

    /**
     * A fault that retrying cannot fix: an unknown event type, an unparseable stored payload, a
     * settlement whose totals contradict its own lines.
     *
     * <p>Retrying these would burn eight attempts and a five-minute cap to arrive at the same
     * answer, while pushing a genuinely fixable backlog behind them.
     */
    public static class NonRetryableEventException extends RuntimeException {
        public NonRetryableEventException(String message) {
            super(message);
        }
    }

    /**
     * Not applicable <b>yet</b>: the event is well formed and the world is simply not ready for it.
     *
     * <p>Distinct from both failure kinds above, and the distinction is the whole point. A retryable
     * fault is the world being unlucky; a non-retryable one is the event being wrong. This is
     * neither: the event is right, and the precondition it needs has not arrived. Treating it as a
     * retryable fault burns the same eight attempts and five minutes that a transient database blip
     * would, and then fails the event — so a settlement that merely overtook its own capture is
     * lost permanently, on a run whose only sin was timing.
     *
     * <p>So a deferred event is re-queued without spending an attempt, and the deferral itself is
     * budgeted: an event that stays blocked eventually does become an operator's problem, and it
     * surfaces on {@code GET /provider/events/failed} like any other dead event.
     */
    public static class BlockedEventException extends RuntimeException {
        public BlockedEventException(String message) {
            super(message);
        }
    }
}