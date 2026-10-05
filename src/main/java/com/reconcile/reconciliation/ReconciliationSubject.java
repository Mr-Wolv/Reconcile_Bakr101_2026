package com.reconcile.reconciliation;

import com.reconcile.payment.PaymentState;
import com.reconcile.shared.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * One unit of reconciliation: everything known about a single provider transaction id, from both
 * sides.
 *
 * <p>The expected side is derived from the <b>ledger</b>, not from the payment row. That is the whole
 * point (see {@code docs/adr/0005}): a bug that writes the wrong amount into both the payment and
 * the ledger would otherwise reconcile clean forever. Deriving the expectation from the ledger means
 * the comparison is between two independent sources, so agreement means something.
 *
 * <p>Either side may hold zero, one, or many records. That is not defensive padding — multiplicity
 * is an outcome in its own right ({@link ReconciliationOutcome#DUPLICATE_PROVIDER_RECORD} and
 * {@link ReconciliationOutcome#AMBIGUOUS_MATCH}), so it has to be representable.
 */
public record ReconciliationSubject(
        String provider,
        String externalTransactionId,
        List<Expected> internal,
        List<ProviderRecord> providerRecords) {

    public ReconciliationSubject {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(externalTransactionId, "externalTransactionId");
        internal = List.copyOf(Objects.requireNonNull(internal, "internal"));
        providerRecords = List.copyOf(Objects.requireNonNull(providerRecords, "providerRecords"));
    }

    /**
     * What our ledger says should be true.
     *
     * @param gross  the capture leg on {@code PSP_CLEARING}
     * @param fee    the fee leg on {@code PLATFORM_FEE_REVENUE}
     * @param net    the merchant leg on {@code MERCHANT_PAYABLE}
     * @param status {@code SETTLED} only if a settlement record covers this payment
     */
    public record Expected(
            String paymentId,
            Money gross,
            Money fee,
            Money net,
            PaymentState status,
            String merchantReference,
            LocalDate settlementDate) {
        public Expected {
            Objects.requireNonNull(gross, "gross");
            Objects.requireNonNull(fee, "fee");
            Objects.requireNonNull(net, "net");
            Objects.requireNonNull(status, "status");
        }
    }

    /**
     * What the provider says is true.
     *
     * @param settlementValidity the validity of the settlement record covering this transaction,
     *                           or {@code null} when no settlement covers it. Not decoration: it is
     *                           the only thing standing between "the PSP paid us and we agree" and
     *                           "the PSP sent a document that contradicts itself", and without it
     *                           the classifier has no way to tell those two apart.
     */
    public record ProviderRecord(
            Money gross,
            Money fee,
            Money net,
            PaymentState status,
            String merchantReference,
            LocalDate settlementDate,
            SettlementValidity settlementValidity) {
        public ProviderRecord {
            Objects.requireNonNull(gross, "gross");
            Objects.requireNonNull(fee, "fee");
            Objects.requireNonNull(net, "net");
            Objects.requireNonNull(status, "status");
        }

        /** True when a settlement covers this transaction and was found to be inconsistent. */
        public boolean coveredByInvalidSettlement() {
            return settlementValidity == SettlementValidity.INVALID;
        }
    }
}