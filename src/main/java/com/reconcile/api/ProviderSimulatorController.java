package com.reconcile.api;

import com.reconcile.service.ProviderSyncService;
import com.reconcile.shared.DomainException;
import com.reconcile.shared.ProblemCode;
import com.reconcile.simulator.SimulatedProviderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The provider simulator's HTTP surface (spec 06 §1).
 *
 * <p>Separate from {@link ProviderWebhookController} because the two authenticate differently in
 * spirit: the webhook endpoint proves who sent it with a signature, and this one assumes the caller
 * is already an operator. The simulator has no secrets of its own — it signs with the shared
 * webhook secret, because that is the only secret a provider and a receiver can both hold.
 */
@RestController
@RequestMapping("/api/v1/provider")
public class ProviderSimulatorController {

    private final SimulatedProviderService simulator;
    private final ProviderSyncService sync;

    public ProviderSimulatorController(SimulatedProviderService simulator, ProviderSyncService sync) {
        this.simulator = simulator;
        this.sync = sync;
    }

    public record RegisterPaymentRequest(
            @NotBlank String merchantReference,
            @NotNull @Min(1) Long grossAmountMinor,
            @NotBlank String currency,
            List<String> behaviour) {
    }

    /**
     * Registers a payment with the simulated PSP and queues the events it would emit.
     *
     * <p>The response carries the provider transaction id, because the caller needs it to create
     * its own payment against the same transaction — that shared id is the entire basis of
     * reconciliation, and generating it on our side instead would make every match trivially
     * perfect.
     */
    @PostMapping("/payments")
    public ResponseEntity<SimulatedProviderService.RegisteredPayment> register(
            @Valid @RequestBody RegisterPaymentRequest request) {

        try {
            return ResponseEntity.status(201).body(simulator.register(
                    request.merchantReference(),
                    request.grossAmountMinor(),
                    request.currency(),
                    request.behaviour()));
        } catch (IllegalArgumentException unknownBehaviour) {
            throw new DomainException(ProblemCode.VALIDATION_FAILED, unknownBehaviour.getMessage());
        }
    }

    /**
     * The provider's own view of settlements in a window — what {@code provider/sync} pulls.
     *
     * <p>A harness can read this directly to see what the pull would return, which is the difference
     * between debugging a sync and guessing at it.
     */
    @GetMapping("/settlements")
    public List<Object> settlements(
            @RequestParam(required = false) Instant windowStart,
            @RequestParam(required = false) Instant windowEnd) {

        Instant end = windowEnd == null ? Instant.now().plusSeconds(86_400) : windowEnd;
        Instant start = windowStart == null ? end.minusSeconds(7 * 86_400) : windowStart;
        return List.copyOf(simulator.settlementsInWindow(start, end));
    }

    /**
     * The canonical event log: what the provider would have pushed.
     *
     * <p>Each event is returned pre-signed and ready to POST verbatim to
     * {@code /api/v1/provider/webhooks}. That is what makes "deliver it twice", "deliver it out of
     * order" and "never deliver it" ordinary HTTP calls in a test.
     */
    @GetMapping("/events")
    public List<SimulatedProviderService.QueuedEvent> events(
            @RequestParam(required = false) String providerTransactionId) {

        return simulator.events(providerTransactionId);
    }

    public record SyncRequest(@NotNull Instant windowStart, @NotNull Instant windowEnd) {
    }

    /**
     * Pulls settlements for a window through the same ingestion path as the webhook.
     *
     * <p>Admin-only: it moves money into the ledger without a signature from the provider, so the
     * only thing standing between a caller and fabricated settlements is authorisation.
     */
    @PostMapping("/sync")
    public ProviderSyncService.SyncResult sync(@Valid @RequestBody SyncRequest request) {
        return sync.sync(request.windowStart(), request.windowEnd(), RequestId.current());
    }
}