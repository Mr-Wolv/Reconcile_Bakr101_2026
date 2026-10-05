package com.reconcile.service;

import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.ledger.FeePolicy;
import com.reconcile.payment.PaymentState;
import com.reconcile.payment.PaymentStateMachine;
import com.reconcile.persistence.jdbc.LedgerWriter;
import com.reconcile.persistence.repository.PaymentRepository;
import com.reconcile.shared.CurrencyCode;
import com.reconcile.shared.DomainException;
import com.reconcile.shared.Money;
import com.reconcile.shared.PageCursor;
import com.reconcile.shared.ProblemCode;
import com.reconcile.shared.Ulid;
import java.time.Clock;
import java.time.Instant;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payment lifecycle.
 *
 * <p>Each command is one database transaction containing its state change, its ledger posting, its
 * state-history row, its audit event and its idempotency record. That is not tidiness — a command
 * that commits its payment row and then fails to post its ledger transaction produces a
 * {@code CAPTURED} payment with no money moved, and no query in this schema will ever find it,
 * because the ledger is the only place the money was supposed to be.
 *
 * <p>Every transition re-reads the payment under {@code SELECT … FOR UPDATE}. The lock, not a
 * version check, is what serialises two concurrent captures of the same payment; the {@code @Version}
 * column is a second line of defence rather than the mechanism (ADR-0002).
 */
@Service
public class PaymentService {

    /** A page never returns more than this, and asking for more is a 400 rather than a truncation. */
    public static final int MAX_PAGE_SIZE = 200;
    public static final int DEFAULT_PAGE_SIZE = 50;

    private final Sql sql;
    private final PaymentRepository payments;
    private final LedgerService ledger;
    private final AuditService audit;
    private final Clock clock;

    public PaymentService(
            Sql sql,
            PaymentRepository payments,
            LedgerService ledger,
            AuditService audit,
            Clock clock) {
        this.sql = sql;
        this.payments = payments;
        this.ledger = ledger;
        this.audit = audit;
        this.clock = clock;
    }

    /** Who is acting, and the request they are acting in. Written onto every audit row. */
    public record Actor(String type, String id) {
    }

    /** Everything a command needs beyond its own arguments. */
    public record CommandContext(Actor actor, String requestId, String idempotencyKey) {

        public static CommandContext of(Actor actor, String requestId, String idempotencyKey) {
            return new CommandContext(actor, requestId, idempotencyKey);
        }

        LedgerWriter.PostingContext posting() {
            return new LedgerWriter.PostingContext(actor.id(), requestId, idempotencyKey);
        }
    }

    // ------------------------------------------------------------------ create

    /**
     * Creates a payment in {@code CREATED}, freezing the fee split.
     *
     * <p>The split is computed once, here, and stored. Recomputing it later would let a fee-policy
     * edit retroactively change what an already-captured payment is worth — and that change should
     * be <i>found</i> by reconciliation as a discrepancy, not manufactured silently by a background
     * job that nobody is looking at.
     */
    @Transactional
    public PaymentView create(
            String merchantReference,
            String description,
            Money gross,
            String provider,
            CommandContext context) {

        return create(merchantReference, description, gross, provider, null, context);
    }

    /**
     * Creates a payment, optionally recording the provider's own transaction id.
     *
     * <p>The id is what reconciles the two sides: a webhook arrives carrying a provider transaction
     * id, and without this column there would be nothing to match it against — every provider event
     * would be unresolvable and reconciliation could only ever report {@code MISSING_INTERNAL}.
     *
     * <p>Optional, and null on the common path: the provider may not have allocated one yet at the
     * moment the merchant creates the payment, and inventing one here would be worse than admitting
     * we do not know it yet. When the provider does allocate one later, it arrives on the
     * {@code payment.created} event.
     */
    @Transactional
    public PaymentView create(
            String merchantReference,
            String description,
            Money gross,
            String provider,
            String providerTransactionId,
            CommandContext context) {

        if (gross.isZero()) {
            throw DomainException.validation("amount", "must be greater than zero", 0);
        }
        Rate rate = activeFeePolicy(gross.currency());
        FeePolicy policy = rate.asPolicy(gross.currency());
        Money fee = policy.feeFor(gross);
        Money net = gross.minus(fee);

        String paymentId = Ulid.of("pay");
        Instant now = clock.instant();

        try {
            sql.sql("""
                    INSERT INTO payments
                        (id, merchant_reference, description, amount_minor, currency,
                         platform_fee_minor, net_amount_minor, fee_policy_id, state, provider,
                         provider_transaction_id, idempotency_key, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CREATED', ?, ?, ?, ?, ?)
                    """)
                    .params(paymentId, merchantReference, description, gross.amountMinor(),
                            gross.currency().code(), fee.amountMinor(), net.amountMinor(),
                            rate.id(), provider, providerTransactionId, context.idempotencyKey(),
                            now, now)
                    .update();
        } catch (org.springframework.dao.DuplicateKeyException alreadyClaimed) {
            // ux_payments_provider_txn did its job: two payments may not share one provider
            // transaction, because reconciliation matches on exactly that identity. Reporting the
            // index violation as-is would answer 500 for a conflict the caller caused and can fix,
            // which trains clients to retry a request that can never succeed.
            throw new DomainException(
                    ProblemCode.PROVIDER_TRANSACTION_ALREADY_CLAIMED,
                    "another payment already carries provider transaction "
                            + providerTransactionId);
        }

        appendHistory(paymentId, null, PaymentState.CREATED, PaymentStateMachine.Trigger.API,
                null, "payment created", context.actor());

        audit.record("USER", context.actor().id(), "PAYMENT_CREATED", "PAYMENT", paymentId,
                context.requestId(),
                java.util.Map.of("amountMinor", gross.amountMinor(),
                        "currency", gross.currency().code(),
                        "provider", provider));

        return read(paymentId);
    }

    // -------------------------------------------------------------- transitions

    /** {@code CREATED → AUTHORIZED}. No ledger effect: no money has moved. */
    @Transactional
    public PaymentView authorize(String paymentId, CommandContext context) {
        return transition(paymentId, PaymentState.AUTHORIZED, PaymentStateMachine.Trigger.API,
                null, context);
    }

    /**
     * {@code AUTHORIZED → CAPTURED}, posting {@code PAYMENT_CAPTURE}.
     *
     * @param expectedAmount when present, must equal the authorised amount <i>in full</i> — both
     *                       the minor-unit figure and its currency. A caller that named a different
     *                       figure is either a bug or an attack, and so is one that named the right
     *                       figure in the wrong currency.
     */
    @Transactional
    public PaymentView capture(String paymentId, Money expectedAmount, CommandContext context) {
        PaymentRow payment = lock(paymentId);
        PaymentStateMachine.require(payment.state(), PaymentState.CAPTURED);

        Money gross = Money.of(payment.amountMinor(), payment.currency());
        // The complete monetary value, not just its size.
        //
        // Comparing amountMinor alone was F-05: a caller capturing EGP 200 with expectedAmount
        // USD 200 passed the guard and got HTTP 200 on a capture of the EGP payment. The guard's own
        // contract says "must equal the authorised amount", and 200 EGP is not 200 USD — comparing
        // only the magnitude accepts a caller who has misunderstood which money they are spending.
        // Money implements equals, and both sides are Money, so this is the whole value.
        if (expectedAmount != null && !expectedAmount.equals(gross)) {
            throw new DomainException(
                    ProblemCode.CAPTURE_AMOUNT_MISMATCH,
                    "expected " + expectedAmount.amountMinor() + " minor units of "
                            + expectedAmount.currency().code() + " but the payment is authorised for "
                            + gross.amountMinor() + " minor units of " + gross.currency().code());
        }
        if (payment.expiresAt() != null && clock.instant().isAfter(payment.expiresAt())) {
            throw new DomainException(
                    ProblemCode.PAYMENT_EXPIRED,
                    "payment " + paymentId + " expired at " + payment.expiresAt());
        }

        Money fee = Money.of(payment.platformFeeMinor(), payment.currency());
        Money net = Money.of(payment.netMinor(), payment.currency());

        // Money moves here, in the same transaction as the state change below. The ledger posts
        // through LedgerService, which joins this transaction rather than opening its own.
        String ledgerTransactionId = ledger.post(
                Postings.capture(paymentId, gross, fee, net, context.actor().id()),
                context.posting());

        linkLedgerTransaction(paymentId, ledgerTransactionId, "CAPTURE");

        applyTransition(payment, PaymentState.CAPTURED, PaymentStateMachine.Trigger.API, null,
                "captured", context.actor(), "PAYMENT_CAPTURED", context.requestId());

        return read(paymentId);
    }

    /**
     * Refunds a payment.
     *
     * <p>Which transaction type this becomes depends on whether the money has been paid to the
     * merchant, and the difference is not cosmetic — see {@link Postings#refundAfterSettlement}.
     *
     * <p>A payment already included in an executed payout is <b>refused</b> (decision D10). The
     * alternative is a refund that drives {@code PLATFORM_CASH} negative and pays out money the
     * platform does not have; recovering it from the merchant is a different feature that v1 does
     * not model. Refusing loudly is the correct boundary, not a limitation to work around.
     */
    @Transactional
    public PaymentView refund(String paymentId, CommandContext context) {
        PaymentRow payment = lock(paymentId);
        PaymentStateMachine.require(payment.state(), PaymentState.REFUNDED);

        if (isInExecutedPayout(paymentId)) {
            throw new DomainException(
                    ProblemCode.PAYMENT_ALREADY_PAID_OUT,
                    "payment " + paymentId + " is part of an executed payout; recovering the money "
                            + "is a merchant-balance operation, which v1 does not model");
        }

        Money gross = Money.of(payment.amountMinor(), payment.currency());
        Money fee = Money.of(payment.platformFeeMinor(), payment.currency());
        Money net = Money.of(payment.netMinor(), payment.currency());

        // Two different ledger stories, and conflating them is a bug the database catches as a
        // mysterious negative-cash error rather than as a wrong posting.
        //
        // CAPTURED -> REFUNDED (T6): the PSP has not paid us yet, so the correct correction is an
        // exact REVERSAL of the capture — Dr PSP_CLEARING / Cr MERCHANT_PAYABLE becomes
        // Dr MERCHANT_PAYABLE / Cr PSP_CLEARING. Nothing has touched PLATFORM_CASH, because nothing
        // has: the money is still receivable.
        //
        // SETTLED -> REFUNDED (T7): the money is ours now and has to go back out, so we post
        // PAYMENT_REFUND_SETTLED, which does credit PLATFORM_CASH.
        String ledgerTransactionId;
        String role;
        if (payment.state() == PaymentState.SETTLED) {
            ledgerTransactionId = ledger.post(
                    Postings.refundAfterSettlement(paymentId, gross, fee, net,
                            context.actor().id()),
                    context.posting());
            role = "REFUND";
        } else {
            String captureTransactionId = captureLedgerTransactionId(paymentId);
            ledgerTransactionId = ledger.reverse(captureTransactionId,
                    "refund of " + paymentId + " before settlement", context.posting());
            role = "REVERSAL";
        }

        linkLedgerTransaction(paymentId, ledgerTransactionId, role);

        applyTransition(payment, PaymentState.REFUNDED, PaymentStateMachine.Trigger.API, null,
                "refunded", context.actor(), "PAYMENT_REFUNDED", context.requestId());

        return read(paymentId);
    }

    /**
     * The capture posting a refund before settlement must reverse.
     *
     * <p>Absent only if the payment is SETTLED without a capture posting, which the transition table
     * makes impossible; the exception is here so that such a state fails loudly instead of
     * reversing an arbitrary transaction.
     */
    private String captureLedgerTransactionId(String paymentId) {
        return sql.sql("""
                SELECT ledger_transaction_id FROM payment_ledger_transactions
                 WHERE payment_id = ? AND role = 'CAPTURE'
                """)
                .param(paymentId)
                .optional((rs, rowNum) -> rs.getString("ledger_transaction_id"))
                .orElseThrow(() -> new DomainException(
                        ProblemCode.INTERNAL_ERROR,
                        "payment " + paymentId + " has no capture posting to reverse"));
    }

    /** Marks a payment failed. Admin-only, and always carries a reason. */
    @Transactional
    public PaymentView fail(String paymentId, String failureCode, String reason, CommandContext context) {
        if (failureCode == null || failureCode.isBlank()) {
            throw DomainException.validation("failureCode", "is required when failing a payment", null);
        }
        PaymentRow payment = lock(paymentId);
        PaymentStateMachine.require(payment.state(), PaymentState.FAILED);

        sql.sql("""
                UPDATE payments
                   SET state = 'FAILED', failure_code = ?, failure_reason = ?,
                       updated_at = ?, version = version + 1
                 WHERE id = ?
                """)
                .params(failureCode, reason, clock.instant(), paymentId)
                .update();

        appendHistory(paymentId, payment.state(), PaymentState.FAILED, PaymentStateMachine.Trigger.API,
                null, reason, context.actor());

        audit.record("USER", context.actor().id(), "PAYMENT_FAILED", "PAYMENT", paymentId,
                context.requestId(),
                java.util.Map.of("failureCode", failureCode));

        return read(paymentId);
    }

    /**
     * {@code CAPTURED → SETTLED}, driven by settlement ingestion rather than by a client.
     *
     * <p>Deliberately not reachable from the public API: settlement money is recognised from a
     * settlement record, never asserted by a caller. {@code CREATED → SETTLED} is absent from the
     * state machine for exactly this reason.
     */
    @Transactional
    /**
     * Settles a payment against a settlement record.
     *
     * @return {@code true} when this call performed the transition, {@code false} when the payment
     *         was already settled. The answer matters: a settlement record may legitimately cover the
     *         same payment twice through a pull/webhook race, and a caller that counts settlements
     *         rather than asking this would post {@code SETTLEMENT_RECEIVED} again on the second
     *         delivery - which is refused by the unique constraint, turning a harmless duplicate
     *         into a permanently failing event.
     */
    public boolean markSettled(String paymentId, String settlementRecordId, String settlementDate,
                               PaymentStateMachine.Trigger trigger, CommandContext context) {

        SettleOutcome outcome = settleIfPossible(paymentId, settlementRecordId, settlementDate,
                trigger, context);
        if (outcome == SettleOutcome.NOT_CAPTURED) {
            // Preserved for callers that treat a refusal as a programming error. The ingestion path
            // asks settleIfPossible directly and must not come through here: this method raises
            // inside a joined transaction, which marks it rollback-only, so a caller that caught the
            // exception and carried on would find its work silently undone at commit.
            PaymentState from = sql.sql("SELECT state FROM payments WHERE id = ?")
                    .param(paymentId)
                    .optional((rs, rowNum) -> PaymentState.valueOf(rs.getString("state")))
                    .orElseThrow();
            PaymentStateMachine.require(from, PaymentState.SETTLED);
        }
        return outcome == SettleOutcome.SETTLED_NOW;
    }

    /** What settling a payment could actually do, decided under its row lock. */
    public enum SettleOutcome {
        /** {@code CAPTURED → SETTLED}. The caller must post the cash movement. */
        SETTLED_NOW,
        /** Already settled. A settlement record may legitimately cover a payment twice. */
        ALREADY_SETTLED,
        /** Refunded after the provider paid us: the settlement says nothing new. Defect 22. */
        SUPERSEDED,
        /** Not captured yet, so the capture this settlement depends on has not been applied. */
        NOT_CAPTURED
    }

    /**
     * Settles a payment if it can be settled, and reports what happened instead of throwing.
     *
     * <p>The decision is made under the row lock, which is the point. A caller that checked the
     * state first and settled second has a window between the two in which another worker can
     * refund the payment — and the settlement then either fails at commit with its transaction
     * already marked rollback-only, or worse, applies against a state nobody chose.
     *
     * <p>{@link #NOT_CAPTURED} is not an error either. A settlement is the provider's account of a
     * transaction that was captured before it was paid, so a payment that has not been captured yet
     * means the capture event is still in flight, not that anything is wrong.
     */
    @Transactional
    public SettleOutcome settleIfPossible(String paymentId, String settlementRecordId,
                                           String settlementDate, PaymentStateMachine.Trigger trigger,
                                           CommandContext context) {

        PaymentRow payment = lock(paymentId);
        if (payment.state() == PaymentState.SETTLED) {
            // A settlement record may legitimately cover a payment twice through a pull/webhook
            // race. Re-applying would be a no-op at best; here it would also break L10.
            return SettleOutcome.ALREADY_SETTLED;
        }
        if (payment.state().isAtLeastAsFarAs(PaymentState.SETTLED)) {
            return SettleOutcome.SUPERSEDED;
        }
        if (!PaymentStateMachine.isAllowed(payment.state(), PaymentState.SETTLED)) {
            return SettleOutcome.NOT_CAPTURED;
        }

        sql.sql("""
                UPDATE payments
                   SET state = 'SETTLED', settlement_record_id = ?, settled_at = ?, updated_at = ?,
                       version = version + 1
                 WHERE id = ?
                """)
                .params(settlementRecordId, clock.instant(), clock.instant(), paymentId)
                .update();

        appendHistory(paymentId, payment.state(), PaymentState.SETTLED, trigger, settlementRecordId,
                "settled by " + settlementRecordId, context.actor());

        audit.record("SERVICE", context.actor().id(), "PAYMENT_SETTLED", "PAYMENT", paymentId,
                context.requestId(), java.util.Map.of("settlementRecordId", settlementRecordId));
        return SettleOutcome.SETTLED_NOW;
    }

    // -------------------------------------------------------------------- reads

    public PaymentView read(String paymentId) {
        return payments.findById(paymentId)
                .map(PaymentService::toView)
                .orElseThrow(() -> DomainException.notFound("payment", paymentId));
    }

    /** Filters for {@code GET /api/v1/payments}. Any field may be null. */
    public record PaymentQuery(
            String state,
            String merchantReference,
            String providerTransactionId,
            String cursor,
            Integer limit) {
    }

    public PaymentView.Page list(PaymentQuery query) {
        int limit = query.limit() == null ? DEFAULT_PAGE_SIZE : query.limit();
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw DomainException.validation("limit",
                    "must be between 1 and " + MAX_PAGE_SIZE, query.limit());
        }
        Cursor decoded = Cursor.decode(query.cursor());

        List<PaymentView> items = payments.page(
                        query.state(),
                        query.merchantReference(),
                        query.providerTransactionId(),
                        decoded == null ? null : decoded.createdAt(),
                        decoded == null ? null : decoded.id(),
                        limit)
                .stream()
                .map(PaymentService::toView)
                .toList();

        // A short page means the end of the data, so there is no next cursor to hand out.
        String nextCursor = items.size() < limit
                ? null
                : Cursor.encode(items.getLast().createdAt(), items.getLast().id());
        return new PaymentView.Page(items, nextCursor);
    }

    /**
     * The payment's whole story, merged from every source that touches it.
     *
     * <p>Four sources, because four different subsystems have something to say about a payment and
     * an investigator needs all four at once: its state history, the postings made against it, the
     * provider events that mentioned it, and how reconciliation judged it. Reading them from
     * separate endpoints is four round trips and four chances to join the answer wrongly by hand.
     *
     * <p><b>Determinism.</b> {@code ORDER BY occurred_at, kind, reference} rather than by
     * timestamp alone: several of these rows genuinely share a timestamp - a capture writes a state
     * history row, a ledger posting and an audit event within the same transaction - and an
     * unstable order would make this endpoint impossible to assert on and impossible to diff
     * between two runs.
     *
     * <p><b>One row per source row.</b> The webhook branch takes its correlation id through a
     * scalar subquery rather than a join. Three deliveries of one event are three rows in
     * {@code provider_event_deliveries} and one in {@code provider_events}, so a join would
     * duplicate the event onto the timeline exactly as many times as it was retried - which is the
     * one thing a deduplicated inbox exists to prevent.
     */
    public List<PaymentView.TimelineEntry> timeline(String paymentId) {
        if (!payments.existsById(paymentId)) {
            throw DomainException.notFound("payment", paymentId);
        }
        return sql.sql("""
                SELECT occurred_at, kind, reference, summary, request_id
                  FROM (
                        SELECT h.occurred_at            AS occurred_at,
                               'STATE'                AS kind,
                               h.id                    AS reference,
                               CASE WHEN h.from_state IS NULL
                                    THEN 'payment created'
                                    ELSE 'state ' || h.from_state || ' -> ' || h.to_state
                               END                    AS summary,
                               h.trigger_ref           AS request_id
                          FROM payment_state_history h
                         WHERE h.payment_id = ?
                        UNION ALL
                        SELECT t.posted_at,
                               'LEDGER',
                               t.id,
                               'posted ' || t.type || ' (' || t.description || ')',
                               t.request_id
                          FROM payment_ledger_transactions plt
                          JOIN ledger_transactions t ON t.id = plt.ledger_transaction_id
                         WHERE plt.payment_id = ?
                        UNION ALL
                        SELECT e.provider_occurred_at,
                               'WEBHOOK',
                               e.event_id,
                               'provider event ' || e.event_type || ' -> ' || e.status,
                               (SELECT d.request_id
                                  FROM provider_event_deliveries d
                                 WHERE d.event_id = e.event_id AND d.request_id IS NOT NULL
                                 ORDER BY d.received_at, d.id
                                 LIMIT 1)
                          FROM provider_events e
                         WHERE e.payload ->> 'providerTransactionId'
                               = (SELECT p.provider_transaction_id FROM payments p WHERE p.id = ?)
                        UNION ALL
                        SELECT r.classified_at,
                               'RECONCILIATION',
                               r.id,
                               'reconciliation ' || r.outcome
                                   || COALESCE(' (delta ' || r.delta_minor || ')',
                                               ' (no delta)'),
                               b.request_id
                          FROM reconciliation_results r
                          JOIN reconciliation_batches b ON b.id = r.batch_id
                         WHERE r.internal_payment_id = ?
                       ) merged
                 ORDER BY occurred_at, kind, reference
                """)
                .params(paymentId, paymentId, paymentId, paymentId)
                .list((rs, rowNum) -> new PaymentView.TimelineEntry(
                        Sql.instant(rs, "occurred_at"),
                        rs.getString("kind"),
                        rs.getString("reference"),
                        rs.getString("summary"),
                        rs.getString("request_id")));
    }

    // ------------------------------------------------------------------ helpers

    private PaymentView transition(String paymentId, PaymentState target,
                                   PaymentStateMachine.Trigger trigger, String reason,
                                   CommandContext context) {
        PaymentRow payment = lock(paymentId);
        PaymentStateMachine.require(payment.state(), target);
        applyTransition(payment, target, trigger, null, reason, context.actor(),
                "PAYMENT_" + target, context.requestId());
        return read(paymentId);
    }

    /**
     * Writes the state change, its history row and its audit event.
     *
     * <p>{@code requestId} is a parameter rather than being read off the {@link Actor} because the
     * two are different things and conflating them is exactly the bug this signature exists to
     * prevent: {@code audit.record} takes a {@code requestId}, and it was being handed
     * {@code actor.id()}. Every authorize, capture and refund therefore wrote
     * {@code request_id = 'api'} into the audit trail instead of the correlation id echoed on the
     * response - so the three most important financial transitions in the system could not be tied
     * back to the request that caused them, which is the one thing an audit trail is for.
     */
    private void applyTransition(PaymentRow payment, PaymentState target,
                                 PaymentStateMachine.Trigger trigger, String settlementRecordId,
                                 String reason, Actor actor, String auditAction, String requestId) {

        sql.sql("""
                UPDATE payments
                   SET state = ?, settlement_record_id = COALESCE(?, settlement_record_id),
                       captured_at = CASE WHEN ? = 'CAPTURED' THEN ? ELSE captured_at END,
                       updated_at = ?,
                       version = version + 1
                 WHERE id = ?
                """)
                .params(target.name(), settlementRecordId, target.name(), clock.instant(),
                        clock.instant(), payment.id())
                .update();

        appendHistory(payment.id(), payment.state(), target, trigger, settlementRecordId, reason,
                actor);

        audit.record(actor.type(), actor.id(), auditAction, "PAYMENT", payment.id(),
                requestId, java.util.Map.of("state", target.name()));
    }

    /** Reads a payment under a row lock, so two concurrent commands cannot both act on it. */
    private PaymentRow lock(String paymentId) {
        List<PaymentRow> rows = sql.sql("""
                SELECT id, amount_minor, currency, platform_fee_minor, net_amount_minor, state,
                       expires_at
                  FROM payments
                 WHERE id = ?
                   FOR UPDATE
                """)
                .param(paymentId)
                .list((rs, rowNum) -> new PaymentRow(
                        rs.getString("id"),
                        rs.getLong("amount_minor"),
                        CurrencyCode.parse(rs.getString("currency")),
                        rs.getLong("platform_fee_minor"),
                        rs.getLong("net_amount_minor"),
                        PaymentState.valueOf(rs.getString("state")),
                        Sql.instant(rs, "expires_at")));

        if (rows.isEmpty()) {
            throw DomainException.notFound("payment", paymentId);
        }
        return rows.getFirst();
    }

    private void appendHistory(String paymentId, PaymentState from, PaymentState to,
                               PaymentStateMachine.Trigger trigger, String triggerRef, String reason,
                               Actor actor) {
        sql.sql("""
                INSERT INTO payment_state_history
                    (id, payment_id, from_state, to_state, trigger_type, trigger_ref, reason,
                     actor_type, actor_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(Ulid.of("psh"), paymentId, from == null ? null : from.name(), to.name(),
                        trigger.name(), triggerRef, reason, actor.type(), actor.id())
                .update();
    }

    private void linkLedgerTransaction(String paymentId, String ledgerTransactionId, String role) {
        sql.sql("""
                INSERT INTO payment_ledger_transactions (payment_id, ledger_transaction_id, role)
                VALUES (?, ?, ?)
                """)
                .params(paymentId, ledgerTransactionId, role)
                .update();
    }

    private boolean isInExecutedPayout(String paymentId) {
        return sql.sql("""
                SELECT 1
                  FROM payout_lines l
                  JOIN payout_records r ON r.id = l.payout_record_id
                 WHERE l.payment_id = ?
                   AND r.status = 'EXECUTED'
                """)
                .param(paymentId)
                .optional(Integer.class)
                .isPresent();
    }

    /**
     * The active fee policy for a currency, and the id it must be referenced by.
     *
     * <p>The seeded policy is denominated in EGP, so a USD payment takes the rate from the default
     * row and the currency from the request. The rate is data; the currency is a property of the
     * payment and cannot be inherited from the schedule. One query serves both needs — looking the id
     * up separately would risk pricing a payment with one policy row and storing another.
     */
    private Rate activeFeePolicy(CurrencyCode currency) {
        List<Rate> rates = sql.sql("""
                SELECT id, code, bps
                  FROM fee_policies
                 WHERE active AND (currency = ? OR code = 'DEFAULT')
                 ORDER BY (currency = ?) DESC
                 LIMIT 1
                """)
                .params(currency.code(), currency.code())
                .list((rs, rowNum) -> new Rate(
                        rs.getString("id"), rs.getString("code"), rs.getInt("bps")));

        if (rates.isEmpty()) {
            throw new DomainException(
                    ProblemCode.INTERNAL_ERROR,
                    "no active fee policy is configured; a payment cannot be priced");
        }
        return rates.getFirst();
    }

    private static PaymentView toView(com.reconcile.persistence.entity.PaymentEntity entity) {
        return new PaymentView(
                entity.getId(),
                entity.getMerchantReference(),
                entity.getDescription(),
                Money.of(entity.getAmountMinor(), entity.getCurrency()),
                Money.of(entity.getPlatformFeeMinor(), entity.getCurrency()),
                Money.of(entity.getNetAmountMinor(), entity.getCurrency()),
                entity.getFeePolicyId(),
                entity.getState(),
                entity.getFailureCode(),
                entity.getFailureReason(),
                entity.getProvider(),
                entity.getProviderTransactionId(),
                entity.getCapturedAt(),
                entity.getSettledAt(),
                entity.getPaidOutAt(),
                entity.getExpiresAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion());
    }

    /**
     * An opaque keyset cursor over {@code (created_at, id)}.
     *
     * <p>A thin, payments-specific view of {@link com.reconcile.shared.PageCursor}, which owns the
     * encoding and the "never guess, always 400" decode policy. The wrapper exists only so the
     * field reads as {@code createdAt} where that is what the column is called; the bytes on the
     * wire are identical to the ledger statement's, which is the point of sharing it.
     */
    record Cursor(Instant createdAt, String id) {

        static String encode(Instant createdAt, String id) {
            return PageCursor.encode(createdAt, id);
        }

        static Cursor decode(String cursor) {
            PageCursor decoded = PageCursor.decode(cursor);
            return decoded == null ? null : new Cursor(decoded.instant(), decoded.id());
        }
    }

    private record Rate(String id, String code, int bps) {

        FeePolicy asPolicy(CurrencyCode currency) {
            return new FeePolicy(code, bps, currency);
        }
    }

    /** A locked payment row, holding only what the commands above need. */
    private record PaymentRow(
            String id,
            long amountMinor,
            CurrencyCode currency,
            long platformFeeMinor,
            long netMinor,
            PaymentState state,
            Instant expiresAt) {
    }
}