package com.reconcile.reconciliation;

import com.reconcile.provider.ProviderEventPayload;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Decides whether a provider's settlement record is fit to act on.
 *
 * <p>Pure, deterministic and free of I/O: the same record and the same lookup always produce the
 * same verdict, which is what makes the ingestion tests able to assert on it directly rather than
 * on the side effects.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>A settlement record is a claim about money: "on this date I paid you this gross, less this
 * fee, leaving this net, for exactly these transactions, in this currency." Several things about
 * that claim can be false while every individual field still looks reasonable — the record can
 * disagree with its own lines, its currency can disagree with the payments it covers, or it can
 * list the same transaction twice. None of those is a column constraint, because each is a
 * <i>relationship</i> between rows.
 *
 * <p>Before this validator, none of them was checked. A validly signed USD settlement covering an
 * EGP payment settled it and posted USD ledger entries against USD clearing; the record's stated
 * net disagreed with the sum of its lines and the difference was discarded; and reconciliation
 * reported the result as {@code MATCHED}, because reconciliation compares a payment against a
 * provider transaction and the settlement record was not part of that comparison at all.
 *
 * <h2>The invariants</h2>
 *
 * <p>Written out in full, because "a settlement should be internally consistent" is not a rule
 * anyone can implement or argue about.
 *
 * <table>
 *   <caption>Settlement record invariants</caption>
 *   <tr><th>ID</th><th>Invariant</th></tr>
 *   <tr><td>S1</td><td>The record has at least one line. A settlement paying nothing settles nothing.</td></tr>
 *   <tr><td>S2</td><td>{@code record.gross == Σ line.gross}</td></tr>
 *   <tr><td>S3</td><td>{@code record.net == Σ line.net}</td></tr>
 *   <tr><td>S4</td><td>{@code record.fee == Σ line.fee}, where {@code line.fee = line.gross − line.net}</td></tr>
 *   <tr><td>S5</td><td>Every line has {@code gross >= net}, so a derived fee is never negative.</td></tr>
 *   <tr><td>S6</td><td>No provider transaction appears on two lines of the same record.</td></tr>
 *   <tr><td>S7</td><td>Every amount is non-negative.</td></tr>
 *   <tr><td>S8</td><td>The record's currency is one the platform supports.</td></tr>
 *   <tr><td>S9</td><td>The record's currency equals the currency of every payment it covers.</td></tr>
 * </table>
 *
 * <p><b>On S2/S3/S4 versus {@code gross = net + fee}.</b> The record-level split is necessary and
 * not sufficient, and the distinction matters. {@code gross = net + fee} says the three stated
 * figures are mutually consistent with <i>each other</i>; it says nothing about whether they
 * describe the same set of transactions as the lines do. A record of gross 10,000 / fee 400 / net
 * 9,600 over a single line of gross 10,000 / net 9,700 satisfies the split perfectly and is still
 * 100 minor units wrong — the line implies a fee of 300. All three of S2, S3 and S4 must therefore
 * hold independently. They are not redundant: S2 and S3 can both hold while S4 fails, which is
 * exactly that case.
 *
 * <p><b>On deriving {@code line.fee}.</b> A line carries a gross and a net and nothing else, so its
 * fee is defined as the difference rather than taken from the provider. That is a definition, not
 * an invented business rule: it is the same relation the platform holds for its own payments
 * ({@code gross == net + fee}, spec 01 §1), so the two sides of the reconciliation are expressed in
 * the same terms. S5 exists because the difference can come out negative, and a negative fee would
 * make S4 arithmetically satisfiable while describing a transaction that paid more than it took.
 *
 * <p><b>On S9 and currency conversion.</b> There is no FX in v1 (spec 01 §9). A settlement in a
 * currency other than the payment's therefore cannot be honoured at all — not by converting, not
 * by posting in the record's currency, not by coercing the payment. The only correct answers are
 * "settle it" or "do not settle it", and when the currencies differ only the second is true.
 *
 * <p>A payment this platform has never seen is <b>not</b> a violation of S9. There is no currency
 * to disagree with, the transaction is stored provider-side for reconciliation to report as
 * {@code MISSING_INTERNAL}, and inventing a currency for it here would be inventing money.
 */
public final class SettlementRecordValidator {

    private SettlementRecordValidator() {
    }

    /**
     * What was wrong, in enough detail to put in front of an operator.
     *
     * @param valid   whether the record may be acted on
     * @param reason  {@code null} when valid; otherwise a stable, specific description
     */
    public record Verdict(boolean valid, String reason) {

        public static Verdict ok() {
            return new Verdict(true, null);
        }

        static Verdict invalid(String reason) {
            return new Verdict(false, reason);
        }

        public SettlementValidity validity() {
            return valid ? SettlementValidity.VALID : SettlementValidity.INVALID;
        }
    }

    /**
     * Validates one settlement record.
     *
     * @param settlement        the record as the provider sent it
     * @param paymentCurrency   the currency of the payment carrying a given provider transaction id,
     *                          or {@link Optional#empty()} when no such payment is known to us
     */
    public static Verdict validate(
            ProviderEventPayload.Settlement settlement,
            Function<String, Optional<String>> paymentCurrency) {

        Objects.requireNonNull(settlement, "settlement");
        Objects.requireNonNull(paymentCurrency, "paymentCurrency");

        // S1. Checked first because every sum below is vacuous over an empty collection, and an
        // empty record would otherwise sail through S2..S4 by summing to zero.
        if (settlement.lines().isEmpty()) {
            return Verdict.invalid("settlement " + settlement.providerSettlementId()
                    + " has no lines; a settlement that pays nothing settles nothing");
        }

        // S7 before anything arithmetic, so a negative figure is reported as itself rather than as
        // a difference it happens to produce.
        if (settlement.grossAmountMinor() < 0
                || settlement.feeAmountMinor() < 0
                || settlement.netAmountMinor() < 0) {
            return Verdict.invalid("settlement " + settlement.providerSettlementId()
                    + " states a negative total: gross=" + settlement.grossAmountMinor()
                    + " fee=" + settlement.feeAmountMinor()
                    + " net=" + settlement.netAmountMinor());
        }

        for (ProviderEventPayload.Line line : settlement.lines()) {
            if (line.grossAmountMinor() < 0 || line.netAmountMinor() < 0) {
                return Verdict.invalid("settlement " + settlement.providerSettlementId()
                        + " line " + line.providerTransactionId()
                        + " states a negative amount: gross=" + line.grossAmountMinor()
                        + " net=" + line.netAmountMinor());
            }
            // S5, before S2..S4 consume the derived fee.
            if (line.netAmountMinor() > line.grossAmountMinor()) {
                return Verdict.invalid("settlement " + settlement.providerSettlementId()
                        + " line " + line.providerTransactionId()
                        + " nets " + line.netAmountMinor()
                        + " on a gross of only " + line.grossAmountMinor()
                        + "; its derived fee would be negative");
            }
        }

        // S8. A currency outside currency_units cannot be stored at all, so it is caught here
        // rather than surfacing as a foreign-key violation from the INSERT.
        String currency = settlement.currency();
        try {
            com.reconcile.shared.CurrencyCode.parse(currency);
        } catch (RuntimeException unsupported) {
            return Verdict.invalid("settlement " + settlement.providerSettlementId()
                    + " is denominated in '" + currency + "', which this platform does not support");
        }

        // S9, before the aggregate checks. A cross-currency record is rejected on its own merits;
        // reporting an aggregate difference first would send an operator to compare totals that were
        // never commensurable.
        Set<String> alreadyChecked = new HashSet<>();
        for (ProviderEventPayload.Line line : settlement.lines()) {
            Optional<String> actual = paymentCurrency.apply(line.providerTransactionId());
            if (actual.isPresent() && !actual.get().equals(currency)) {
                return Verdict.invalid("settlement " + settlement.providerSettlementId()
                        + " is in " + currency + " but transaction "
                        + line.providerTransactionId() + " was captured in " + actual.get()
                        + "; v1 does not convert between currencies, so this settlement cannot be"
                        + " honoured");
            }
            alreadyChecked.add(line.providerTransactionId());
        }

        // S6. Reported before the sums so a duplicated transaction is not silently double-counted
        // into a total that then disagrees for the wrong reason.
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (ProviderEventPayload.Line line : settlement.lines()) {
            occurrences.merge(line.providerTransactionId(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : occurrences.entrySet()) {
            if (entry.getValue() > 1) {
                return Verdict.invalid("settlement " + settlement.providerSettlementId()
                        + " lists transaction " + entry.getKey() + " on "
                        + entry.getValue() + " lines; a record cannot settle the same transaction twice");
            }
        }

        long lineGross = 0;
        long lineNet = 0;
        for (ProviderEventPayload.Line line : settlement.lines()) {
            lineGross += line.grossAmountMinor();
            lineNet += line.netAmountMinor();
        }
        // The fee a line implies is the difference between what it took and what it paid. Derived,
        // never supplied: a line carries a gross and a net and nothing else.
        long lineFee = lineGross - lineNet;

        // S2, S3, S4 — each reported on its own, because a provider whose record is wrong in more
        // than one way should be told about all of it in one pass rather than one field per
        // redelivery.
        StringBuilder problems = new StringBuilder();
        appendIf(problems, settlement.grossAmountMinor() != lineGross,
                "states gross " + settlement.grossAmountMinor()
                        + " but its " + settlement.lines().size() + " line(s) total " + lineGross);
        appendIf(problems, settlement.netAmountMinor() != lineNet,
                "states net " + settlement.netAmountMinor()
                        + " but its lines total " + lineNet);
        appendIf(problems, settlement.feeAmountMinor() != lineFee,
                "states fee " + settlement.feeAmountMinor()
                        + " but its lines imply " + lineFee
                        + " (gross " + lineGross + " − net " + lineNet + ")");

        if (problems.length() > 0) {
            return Verdict.invalid("settlement " + settlement.providerSettlementId()
                    + " contradicts its own lines: " + problems);
        }

        return Verdict.ok();
    }

    private static void appendIf(StringBuilder into, boolean condition, String problem) {
        if (!condition) {
            return;
        }
        if (into.length() > 0) {
            into.append("; ");
        }
        into.append(problem);
    }
}