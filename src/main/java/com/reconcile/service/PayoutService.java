package com.reconcile.service;

import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.shared.CurrencyCode;
import com.reconcile.shared.DomainException;
import com.reconcile.shared.Money;
import com.reconcile.shared.ProblemCode;
import com.reconcile.shared.Ulid;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pays a merchant the net amounts of a set of settled payments.
 *
 * <p>The caller names the payments; <b>this service reads every amount from the ledger</b>. A
 * payout request carries no figures at all, so a caller cannot pay out an arbitrary number — which
 * is the single most important property of this endpoint, because "trust the amount in the
 * request" is how a payout endpoint becomes a withdrawal machine.
 *
 * <p>Everything happens in one transaction: lock the payments, validate, lock the accounts in the
 * ADR-0002 order, post one {@code MERCHANT_PAYOUT} for the record total, insert the lines, stamp
 * {@code paid_out_at}, audit.
 *
 * <p>L11 — one payout per payment, ever — is enforced by {@code ux_payout_line_payment}, a unique
 * index on {@code payment_id} alone. That means the guarantee does not depend on this service
 * checking anything: a retry with a different idempotency key, or two concurrent payout requests
 * covering the same payment, both fail at the database rather than at a line of Java that a future
 * change could skip.
 */
@Service
public class PayoutService {

    private final Sql sql;
    private final LedgerService ledger;
    private final AuditService audit;
    private final PaymentService payments;
    private final Clock clock;

    public PayoutService(
            Sql sql,
            LedgerService ledger,
            AuditService audit,
            PaymentService payments,
            Clock clock) {
        this.sql = sql;
        this.ledger = ledger;
        this.audit = audit;
        this.payments = payments;
        this.clock = clock;
    }

    /** What was created, including the posting that moved the money. */
    public record PayoutView(
            String id,
            String merchantReference,
            Money totalNet,
            int lineCount,
            String status,
            String ledgerTransactionId,
            Instant executedAt,
            List<Line> lines) {

        public PayoutView {
            lines = List.copyOf(lines);
        }

        public record Line(String paymentId, Money net, int lineNo) {
        }
    }

    /**
     * Executes a payout.
     *
     * @throws DomainException {@code PAYOUT_PAYMENT_NOT_SETTLED}, {@code PAYOUT_MERCHANT_MISMATCH},
     *         {@code PAYOUT_CURRENCY_MISMATCH}, {@code PAYOUT_PAYMENT_ALREADY_PAID},
     *         {@code PAYOUT_INSUFFICIENT_CASH}
     */
    @Transactional
    public PayoutView execute(
            String merchantReference,
            List<String> paymentIds,
            PaymentService.CommandContext context) {

        // A duplicate id in one request would otherwise be paid twice, and the unique index would
        // reject the second line with an error that looks like a concurrency bug rather than a
        // malformed request.
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(paymentIds));
        if (distinct.isEmpty()) {
            throw DomainException.validation("paymentIds", "must name at least one payment", null);
        }
        if (distinct.size() != paymentIds.size()) {
            throw DomainException.validation(
                    "paymentIds", "must not repeat a payment id", paymentIds);
        }

        // Lock the payments in id order: the same reasoning as the account lock order — two
        // concurrent payouts naming overlapping payments must not deadlock.
        List<PayoutCandidate> candidates = lockPayments(distinct);

        CurrencyCode currency = validate(candidates, merchantReference);

        Money total = Money.zero(currency);
        for (PayoutCandidate candidate : candidates) {
            total = total.plus(Money.of(candidate.netAmountMinor(), currency));
        }

        requireSufficientCash(total, currency);

        String payoutId = Ulid.of("po");
        String ledgerTransactionId = ledger.post(
                Postings.merchantPayout(payoutId, total, merchantReference), context.posting());

        Instant now = clock.instant();
        sql.sql("""
                INSERT INTO payout_records
                    (id, merchant_reference, currency, total_net_minor, line_count, status,
                     ledger_transaction_id, executed_at, created_by_actor, request_id, created_at)
                VALUES (?, ?, ?, ?, ?, 'EXECUTED', ?, ?, ?, ?, ?)
                """)
                .params(payoutId, merchantReference, currency.code(), total.amountMinor(),
                        candidates.size(), ledgerTransactionId, now, context.actor().id(),
                        context.requestId(), now)
                .update();

        int lineNo = 1;
        for (PayoutCandidate candidate : candidates) {
            sql.sql("""
                    INSERT INTO payout_lines (id, payout_record_id, payment_id, line_net_minor, line_no)
                    VALUES (?, ?, ?, ?, ?)
                    """)
                    .params(Ulid.of("pl"), payoutId, candidate.id(), candidate.netAmountMinor(), lineNo)
                    .update();

            sql.sql("UPDATE payments SET paid_out_at = ?, updated_at = ?, version = version + 1 "
                    + " WHERE id = ?")
                    .params(now, now, candidate.id())
                    .update();
            lineNo++;
        }

        audit.record("USER", context.actor().id(), "PAYOUT_EXECUTED", "PAYOUT_RECORD", payoutId,
                context.requestId(),
                java.util.Map.of("merchantReference", merchantReference,
                        "totalNetMinor", total.amountMinor(),
                        "currency", currency.code(),
                        "lineCount", candidates.size(),
                        "ledgerTransactionId", ledgerTransactionId));

        return new PayoutView(payoutId, merchantReference, total, candidates.size(), "EXECUTED",
                ledgerTransactionId, now, lines(candidates, currency));
    }

    public PayoutView read(String payoutId) {
        Record_ record = readRecord(payoutId);
        List<StoredLine> lines = sql.sql("""
                SELECT payment_id, line_net_minor, line_no
                  FROM payout_lines
                 WHERE payout_record_id = ?
                 ORDER BY line_no
                """)
                .param(payoutId)
                .list((rs, rowNum) -> new StoredLine(
                        rs.getString("payment_id"),
                        rs.getLong("line_net_minor"),
                        rs.getInt("line_no")));

        List<PayoutView.Line> viewLines = lines.stream()
                .map(line -> new PayoutView.Line(line.paymentId(),
                        Money.of(line.netAmountMinor(), record.currency()), line.lineNo()))
                .toList();

        return new PayoutView(record.id(), record.merchantReference(),
                Money.of(record.totalNetMinor(), record.currency()), record.lineCount(),
                record.status(), record.ledgerTransactionId(), record.executedAt(), viewLines);
    }

    private Record_ readRecord(String payoutId) {
        return sql.sql("""
                SELECT id, merchant_reference, currency, total_net_minor, line_count, status,
                       ledger_transaction_id, executed_at
                  FROM payout_records
                 WHERE id = ?
                """)
                .param(payoutId)
                .optional((rs, rowNum) -> new Record_(
                        rs.getString("id"),
                        rs.getString("merchant_reference"),
                        CurrencyCode.parse(rs.getString("currency")),
                        rs.getLong("total_net_minor"),
                        rs.getInt("line_count"),
                        rs.getString("status"),
                        rs.getString("ledger_transaction_id"),
                        Sql.instant(rs, "executed_at")))
                .orElseThrow(() -> DomainException.notFound("payout", payoutId));
    }

    /**
     * Locks the named payments and checks everything that can be checked without moving money.
     *
     * <p>Every check here happens before the ledger posting so that a rejected payout leaves no
     * trace at all, rather than a reversal to undo a posting that should never have been made.
     */
    private List<PayoutCandidate> lockPayments(List<String> paymentIds) {
        List<PayoutCandidate> locked = sql.sql("""
                SELECT id, net_amount_minor, currency, state, merchant_reference
                  FROM payments
                 WHERE id IN (:ids)
                 ORDER BY id
                   FOR UPDATE
                """)
                .param("ids", paymentIds)
                .list((rs, rowNum) -> new PayoutCandidate(
                        rs.getString("id"),
                        rs.getLong("net_amount_minor"),
                        rs.getString("currency"),
                        rs.getString("state"),
                        rs.getString("merchant_reference")));

        if (locked.size() != paymentIds.size()) {
            List<String> found = locked.stream().map(PayoutCandidate::id).toList();
            List<String> missing = paymentIds.stream().filter(id -> !found.contains(id)).toList();
            throw DomainException.notFound("payment", String.join(", ", missing));
        }

        for (PayoutCandidate candidate : locked) {
            if (!"SETTLED".equals(candidate.state())) {
                throw new DomainException(
                        ProblemCode.PAYOUT_PAYMENT_NOT_SETTLED,
                        "payment " + candidate.id() + " is " + candidate.state()
                                + "; only SETTLED payments can be paid out");
            }
            if (alreadyPaid(candidate.id())) {
                throw new DomainException(
                        ProblemCode.PAYOUT_PAYMENT_ALREADY_PAID,
                        "payment " + candidate.id() + " is already part of an executed payout");
            }
        }
        return locked;
    }

    /**
     * Refuses a payout the platform cannot cover, before anything is posted.
     *
     * <p>L8 would catch it anyway: {@code tg_asset_non_negative} refuses the posting, and the whole
     * transaction rolls back. But it refuses it as a constraint violation, which reaches the client
     * as a {@code 500}. "You cannot pay this out" is a business decision with a documented
     * {@code 409 PAYOUT_INSUFFICIENT_CASH}, and a client that cannot tell it from a genuine system
     * failure escalates the wrong thing.
     *
     * <p>The trigger stays as the last line of defence. It has to: this check reads a balance the
     * database could change between the read and the posting, and only the trigger sees the state
     * at the moment the money would actually move.
     */
    private void requireSufficientCash(Money total, CurrencyCode currency) {
        long available = sql.sql("""
                SELECT b.balance_minor FROM account_balances b
                  JOIN ledger_accounts a ON a.id = b.account_id
                 WHERE a.code = 'PLATFORM_CASH' AND b.currency = ?
                """)
                .param(currency.code())
                .optional((rs, rowNum) -> rs.getLong("balance_minor"))
                .orElse(0L);

        if (total.amountMinor() > available) {
            throw new DomainException(
                    ProblemCode.PAYOUT_INSUFFICIENT_CASH,
                    "the payout of " + total.amountMinor() + " " + currency.code()
                            + " exceeds the available cash of " + available + " " + currency.code());
        }
    }

    private CurrencyCode validate(List<PayoutCandidate> candidates, String merchantReference) {
        for (PayoutCandidate candidate : candidates) {
            if (!candidate.merchantReference().equals(merchantReference)) {
                throw new DomainException(
                        ProblemCode.PAYOUT_MERCHANT_MISMATCH,
                        "payment " + candidate.id() + " belongs to merchant "
                                + candidate.merchantReference() + ", not " + merchantReference);
            }
        }
        CurrencyCode currency = CurrencyCode.parse(candidates.getFirst().currency());
        for (PayoutCandidate candidate : candidates) {
            if (!CurrencyCode.parse(candidate.currency()).equals(currency)) {
                throw new DomainException(
                        ProblemCode.PAYOUT_CURRENCY_MISMATCH,
                        "a payout must be in a single currency; payment " + candidate.id()
                                + " is in " + candidate.currency() + " but the payout is in " + currency);
            }
        }
        return currency;
    }

    private boolean alreadyPaid(String paymentId) {
        return sql.sql("SELECT 1 FROM payout_lines WHERE payment_id = ?")
                .param(paymentId)
                .optional(Integer.class)
                .isPresent();
    }

    private List<PayoutView.Line> lines(List<PayoutCandidate> candidates, CurrencyCode currency) {
        List<PayoutView.Line> lines = new ArrayList<>(candidates.size());
        int lineNo = 1;
        for (PayoutCandidate candidate : candidates) {
            lines.add(new PayoutView.Line(candidate.id(),
                    Money.of(candidate.netAmountMinor(), currency), lineNo++));
        }
        return lines;
    }

    /** A locked payment row considered for payout. */
    private record PayoutCandidate(
            String id,
            long netAmountMinor,
            String currency,
            String state,
            String merchantReference) {
    }

    /** One stored payout line. */
    private record StoredLine(String paymentId, long netAmountMinor, int lineNo) {
    }

    private record Record_(
            String id,
            String merchantReference,
            CurrencyCode currency,
            long totalNetMinor,
            int lineCount,
            String status,
            String ledgerTransactionId,
            Instant executedAt) {
    }
}