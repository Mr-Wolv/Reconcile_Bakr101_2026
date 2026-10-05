package com.reconcile.simulator;

import com.reconcile.provider.Providers;
import com.reconcile.config.ReconcileProperties;
import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.provider.RemoteSettlement;
import com.reconcile.provider.WebhookSignature;
import com.reconcile.shared.CurrencyCode;
import com.reconcile.shared.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * A stand-in for the payment provider, controllable over HTTP (spec 06 §1).
 *
 * <p>This exists because every interesting failure in this system is a <i>provider</i> failure, and
 * a failure you cannot reproduce is a failure you cannot fix. Each behaviour here maps to one row of
 * the failure matrix, so the designed-for disaster is one HTTP call and therefore one automated
 * test:
 *
 * <pre>
 *   DUPLICATE_WEBHOOK     the same event id appears twice  (F-11, I-WEB-02)
 *   DELAYED_WEBHOOK       the delivery timestamp is stale   (F-12, I-WEB-04)
 *   WRONG_AMOUNT          the provider reports a different figure (F-30, C-RECON-02)
 *   PARTIAL_SETTLEMENT    a settlement line covers only part (F-33, C-RECON-12)
 *   OMIT_FROM_SETTLEMENT  no settlement ever arrives       (F-25)
 *   DUPLICATE_SETTLEMENT  the same settlement arrives twice (C-PROV-04)
 *   TIMEOUT               the settlement endpoint hangs     (F-41, C-PROV-02)
 *   OUT_OF_ORDER          refund is reported before capture (I-WEB-06)
 * </pre>
 *
 * <p>Events are <b>queued, not pushed</b>. A real PSP sends a webhook; this one hands the harness a
 * signed event to POST wherever it likes, so a test can hold delivery back, reorder it, deliver it
 * three times, or never deliver it at all. That is the whole difference between testing the
 * ingestion contract and testing that a mocked HTTP client returns the bytes it was told to.
 *
 * <p>State is in memory and lost on restart. That is honest for a simulator and it is also a
 * property worth stating rather than hiding: nothing in the reconciliation engine may depend on it,
 * because a real provider's memory is shorter than ours.
 */
@Service
public class SimulatedProviderService {

    /** The behaviours this simulator understands. Anything else is a 400, not a shrug. */
    public enum Behaviour {
        DUPLICATE_WEBHOOK,
        DELAYED_WEBHOOK,
        WRONG_AMOUNT,
        PARTIAL_SETTLEMENT,
        OMIT_FROM_SETTLEMENT,
        DUPLICATE_SETTLEMENT,
        TIMEOUT,
        OUT_OF_ORDER;

        public static Optional<Behaviour> parse(String value) {
            for (Behaviour behaviour : values()) {
                if (behaviour.name().equalsIgnoreCase(value)) {
                    return Optional.of(behaviour);
                }
            }
            return Optional.empty();
        }
    }

    /** One queued event, already rendered to the exact bytes that will be signed. */
    public record QueuedEvent(
            String eventId,
            String type,
            String body,
            Instant occurredAt,
            /** Seconds to age the delivery timestamp by; 0 for everything but {@code DELAYED_WEBHOOK}. */
            int timestampOffsetSeconds,
            String signTimestamp,
            String signature) {
    }

    /** One registered payment, and the transactions the provider holds for it. */
    public record RegisteredPayment(
            String externalTransactionId,
            String merchantReference,
            long grossAmountMinor,
            String currency,
            List<String> behaviours) {
    }

    private static final String PROVIDER = Providers.SIMULATED_PSP;

    /**
     * The simulated PSP's own fee schedule: 3.00% of gross, the same 300 bps the platform seeds.
     *
     * <p>Expressed in basis points rather than as {@code 0.03} so that the arithmetic is exact and
     * so that a change to the platform's fee policy has an obvious place to be mirrored.
     */
    static final int PROVIDER_FEE_BPS = 300;

    private final ReconcileProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;

    private final Map<String, RegisteredPayment> payments = new LinkedHashMap<>();
    private final Map<String, List<QueuedEvent>> eventsByTransaction = new LinkedHashMap<>();
    private final List<RemoteSettlement> settlements = new ArrayList<>();

    /**
     * Minted ids are unique per process lifetime, and are seeded at construction to continue past
     * everything this database already holds. See {@link #highestMintedId}.
     */
    private final AtomicLong sequence;

    /** The currency the simulator reports in. Fixed per registration; see {@link #register}. */
    private String currencyOf = "EGP";

    public SimulatedProviderService(
            ReconcileProperties properties, ObjectMapper mapper, Clock clock, Sql sql) {
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
        this.sequence = new AtomicLong(highestMintedId(sql));
    }

    /**
     * The highest provider-side id already persisted, across all three id spaces this class mints.
     *
     * <p>A real provider's transaction ids are unique forever. A counter that starts at zero on
     * every process start is not: restart the application, run the same demonstration twice, and the
     * second run is handed a provider transaction id that a payment from the first run already
     * owns. The caller's only symptom is a {@code 409 PROVIDER_TRANSACTION_ALREADY_CLAIMED} against
     * an id it had never seen before and had just been given — a domain error manufactured by a
     * test fixture, which is worse than no simulator at all because it looks like a real defect.
     *
     * <p>Read once, at construction. That is enough: this process mints every id it will mint from
     * this point on, and nothing else writes these columns.
     */
    private static long highestMintedId(Sql sql) {
        return sql.sql("""
                SELECT GREATEST(
                    COALESCE((SELECT MAX(SUBSTRING(provider_transaction_id FROM 5)::bigint)
                                FROM provider_transactions
                               WHERE provider_transaction_id ~ '^psp_[0-9]+$'), 0),
                    COALESCE((SELECT MAX(SUBSTRING(provider_transaction_id FROM 5)::bigint)
                                FROM payments
                               WHERE provider_transaction_id ~ '^psp_[0-9]+$'), 0),
                    COALESCE((SELECT MAX(SUBSTRING(provider_settlement_id FROM 7)::bigint)
                                FROM settlement_records
                               WHERE provider_settlement_id ~ '^psset_[0-9]+$'), 0),
                    COALESCE((SELECT MAX(SUBSTRING(event_id FROM 5)::bigint)
                                FROM provider_events
                               WHERE event_id ~ '^evt_[0-9]+$'), 0)
                ) AS highest
                """)
                .optional((rs, rowNum) -> ((Number) rs.getObject("highest")).longValue())
                .orElse(0L);
    }

    /**
     * Registers a payment and queues the events the provider would emit for it.
     *
     * <p>All three are queued at once because the simulator has no opinion about our API's timing;
     * what it models is the provider's <i>content</i>. The harness chooses which to deliver and when,
     * which is exactly the control a delivery test needs.
     */
    public synchronized RegisteredPayment register(
            String merchantReference, long grossAmountMinor, String currency,
            List<String> requestedBehaviours) {

        List<String> behaviours = normalise(requestedBehaviours);
        String externalId = "psp_" + nextToken();

        // The provider's fee model, matching ours by default so a clean run reconciles clean.
        //
        // Integer basis-point arithmetic, not `grossAmountMinor * 0.03d`. That literal was F-10: it
        // is a binary floating-point value, and gross * 0.03d rounds to the nearest long after the
        // multiply has already lost precision. For the amounts this simulator deals in it happens to
        // agree with the exact answer, which is exactly why it survived — a scan that only looked for
        // the words double/float could not see it, so the "no floating-point money anywhere in main
        // sources" claim was false while its own test passed.
        //
        // The policy is expressed in basis points because that is how the platform expresses it
        // (spec 01 §1, FeePolicy, and the 300 bps seed row), so the two sides of a reconciliation
        // are now computed the same way rather than merely arriving at the same number.
        long fee = Money.of(grossAmountMinor, CurrencyCode.parse(currency))
                .multipliedByBps(PROVIDER_FEE_BPS)
                .amountMinor();
        long net = grossAmountMinor - fee;

        long reportedGross = grossAmountMinor;
        long reportedNet = net;
        if (behaviours.contains(Behaviour.WRONG_AMOUNT.name())) {
            // 3.00 short, which is the canonical reconciliation finding in the failure matrix.
            reportedGross = grossAmountMinor - 300;
            reportedNet = net - 300;
        }

        currencyOf = currency;
        RegisteredPayment payment = new RegisteredPayment(
                externalId, merchantReference, grossAmountMinor, currency, behaviours);
        payments.put(externalId, payment);
        List<QueuedEvent> events = new ArrayList<>();

        // One offset for the whole payment: a provider whose deliveries are late is late for all
        // of them, and singling out one event would test the signature check on a schedule that
        // cannot actually happen.
        int offset = behaviours.contains(Behaviour.DELAYED_WEBHOOK.name()) ? -600 : 0;

        String capturedId = nextEventId("evt");
        events.add(paymentEvent(capturedId, "payment.captured", externalId, merchantReference,
                reportedGross, fee, reportedNet, "CAPTURED", offset));

        String createdId = nextEventId("evt");
        events.add(paymentEvent(createdId, "payment.created", externalId, merchantReference,
                reportedGross, fee, reportedNet, "AUTHORIZED", offset));

        if (behaviours.contains(Behaviour.OUT_OF_ORDER.name())) {
            // A refund reported before the capture that authorised it. The provider is wrong here;
            // the engine must refuse to apply it and must not corrupt the state it already has.
            String refundId = nextEventId("evt");
            events.add(0, paymentEvent(refundId, "payment.refunded", externalId, merchantReference,
                    reportedGross, fee, reportedNet, "REFUNDED", offset));
        }

        if (behaviours.contains(Behaviour.DUPLICATE_WEBHOOK.name())) {
            // Same event id, so the delivery dedupe is what has to catch this. A simulator that
            // minted a second id would be testing the wrong thing.
            events.add(sign(capturedId, "payment.captured",
                    paymentBody("payment.captured", externalId, merchantReference,
                            reportedGross, fee, reportedNet, "CAPTURED"),
                    clock.instant(), offset));
        }

        eventsByTransaction.put(externalId, events);

        if (!behaviours.contains(Behaviour.OMIT_FROM_SETTLEMENT.name())) {
            RemoteSettlement settlement = settlementFor(payment, reportedGross, fee, reportedNet, behaviours);
            settlements.add(settlement);
            String settlementEventId = nextEventId("evt");
            eventsByTransaction.get(externalId).add(queued(settlementEventId, "settlement.paid",
                    mapper.writeValueAsString(settlement.asEventPayload(PROVIDER, clock.instant())),
                    behaviours));

            if (behaviours.contains(Behaviour.DUPLICATE_SETTLEMENT.name())) {
                // A different event id carrying the same settlement: two deliveries, one record.
                // The uniqueness constraint, not the event dedupe, is what has to hold here.
                String repeatId = nextEventId("evt");
                eventsByTransaction.get(externalId).add(queued(repeatId, "settlement.paid",
                        mapper.writeValueAsString(settlement.asEventPayload(PROVIDER, clock.instant())),
                        behaviours));
            }
        }

        return payment;
    }

    private RemoteSettlement settlementFor(RegisteredPayment payment, long gross, long fee, long net,
            List<String> behaviours) {

        // A provider that pays less books the shortfall as further fees rather than sending a
        // record that fails its own gross = net + fee identity. That is both what a real one does
        // and what makes the finding subtle: the record is internally consistent, so only
        // comparing our figure against theirs can detect it (C-RECON-12).
        long lineGross = gross;
        long lineNet = net;
        long recordFee = fee;
        if (behaviours.contains(Behaviour.PARTIAL_SETTLEMENT.name())) {
            lineNet = net - 300;
            recordFee = fee + 300;
        }

        return new RemoteSettlement(
                "psset_" + nextToken(),
                LocalDate.now(clock),
                payment.currency(),
                lineGross,
                recordFee,
                lineNet,
                List.of(new RemoteSettlement.RemoteLine(
                        payment.externalTransactionId(), lineGross, lineNet)));
    }

    /**
     * The settlement reports the provider holds for a window.
     *
     * <p>{@code TIMEOUT} is honoured here rather than thrown: the endpoint hangs, and the caller
     * (a real HTTP client, or the sync path) must experience an actual timeout. A simulator that
     * returned an error immediately would test the wrong failure.
     */
    public synchronized List<RemoteSettlement> settlementsInWindow(
            Instant windowStart, Instant windowEnd) {

        if (hasTimeoutBehaviour()) {
            try {
                // Long enough that any sane client timeout fires first.
                Thread.sleep(30_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        // Everything the simulator holds is "recent"; a window in the past simply returns nothing.
        return settlements.stream()
                .filter(s -> !s.settlementDate().isBefore(LocalDate.now(clock))
                        && !windowEnd.isBefore(clock.instant())
                        && !windowStart.isAfter(clock.instant()))
                .toList();
    }

    /** Whether any registered payment asked the provider to hang, which is a global switch. */
    public synchronized boolean hasTimeoutBehaviour() {
        return payments.values().stream()
                .anyMatch(p -> p.behaviours().contains(Behaviour.TIMEOUT.name()));
    }

    /** The queued events for one provider transaction, or every event when {@code id} is null. */
    public synchronized List<QueuedEvent> events(String externalTransactionId) {
        if (externalTransactionId == null) {
            return eventsByTransaction.values().stream().flatMap(List::stream).toList();
        }
        return eventsByTransaction.getOrDefault(externalTransactionId, List.of());
    }

    /** Removes every registered payment and queued event. */
    public synchronized void reset() {
        payments.clear();
        eventsByTransaction.clear();
        settlements.clear();
        sequence.set(0);
    }

    // ------------------------------------------------------------------ internals

    private QueuedEvent queued(String eventId, String type, String body, List<String> behaviours) {
        Instant now = clock.instant();
        int offset = behaviours.contains(Behaviour.DELAYED_WEBHOOK.name()) ? -600 : 0;
        return sign(eventId, type, body, now, offset);
    }

    private QueuedEvent paymentEvent(String eventId, String type, String externalId,
            String merchantReference, long gross, long fee, long net, String status, int offset) {

        String body = paymentBody(type, externalId, merchantReference, gross, fee, net, status);
        return sign(eventId, type, body, clock.instant(), offset);
    }

    /**
     * The exact bytes of a payment event.
     *
     * <p>Serialised through the same mapper the receiver uses, but deliberately never round-tripped
     * through the receiver's own record before being signed: the signature has to cover the bytes
     * that will actually be delivered, and re-serialising them here is precisely the bug
     * {@code I-WEB-05} exists to catch.
     */
    private String paymentBody(String type, String externalId, String merchantReference, long gross,
            long fee, long net, String status) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("occurredAt", clock.instant().toString());
        body.put("providerTransactionId", externalId);
        body.put("merchantReference", merchantReference);
        body.put("gross", amount(gross));
        body.put("fee", amount(fee));
        body.put("net", amount(net));
        body.put("status", status);
        return mapper.writeValueAsString(body);
    }

    private Map<String, Object> amount(long amountMinor) {
        return Map.of("amountMinor", amountMinor, "currency", currencyOf);
    }

    /**
     * Signs the event as the provider would.
     *
     * <p>The signature is computed here, over the exact bytes that are handed over, so a harness can
     * POST the body verbatim without reproducing the provider's signing rule — and cannot
     * accidentally produce a re-serialisation, which is the mistake {@code I-WEB-05} exists to catch.
     *
     * <p>The event id is signed alongside them, because it is the key the receiving side deduplicates
     * on. Signing it here is what stops a harness from producing a request whose body is authentic and
     * whose identity is not.
     */
    private QueuedEvent sign(String eventId, String type, String body, Instant now, int offsetSeconds) {
        String secret = properties.provider().webhookSecret();
        String timestamp = String.valueOf(now.plusSeconds(offsetSeconds).getEpochSecond());
        String signature = WebhookSignature.sign(secret, timestamp, eventId,
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new QueuedEvent(eventId, type, body, now, offsetSeconds, timestamp, signature);
    }

    private String nextEventId(String prefix) {
        return prefix + "_" + nextToken();
    }

    private String nextToken() {
        return String.format("%013d", sequence.incrementAndGet());
    }

    private static List<String> normalise(List<String> behaviours) {
        if (behaviours == null || behaviours.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String behaviour : behaviours) {
            if (behaviour == null || behaviour.isBlank()) {
                continue;
            }
            if (Behaviour.parse(behaviour).isEmpty()) {
                throw new IllegalArgumentException(
                        "unknown provider behaviour '" + behaviour.toUpperCase(Locale.ROOT)
                                + "'; supported: " + java.util.Arrays.toString(Behaviour.values()));
            }
            out.add(Behaviour.parse(behaviour).orElseThrow().name());
        }
        return List.copyOf(out);
    }
}