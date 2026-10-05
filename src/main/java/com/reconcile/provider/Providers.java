package com.reconcile.provider;

import com.reconcile.shared.DomainException;
import com.reconcile.shared.ProblemCode;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The providers this deployment knows about.
 *
 * <p>The provider was a {@code "SIMULATED_PSP"} string literal in four separate places: the payment
 * default, the webhook controller, the sync service, and the simulator itself. Four copies of one
 * identifier is three chances to rename it in two of them, and the failure is silent in the worst
 * direction — rows written under one spelling are invisible to a reconciliation batch filtered on
 * the other, so a payment simply never reconciles and nobody is told why.
 *
 * <p>This is the step that makes the provider data rather than a constant scattered through the
 * code. It is deliberately not a table and not a per-request lookup: there is one provider today and
 * the schema already carries {@code provider} on every table it needs to. When a second arrives,
 * this is the one file that changes.
 *
 * <p>An unrecognised provider is rejected rather than stored. A payment under a provider nobody
 * recognises is not a payment this system can reconcile, and accepting it produces exactly the
 * silent-forever failure described above.
 */
public final class Providers {

    /** The simulated PSP: the provider this build ships with. */
    public static final String SIMULATED_PSP = "SIMULATED_PSP";

    private static final Set<String> KNOWN = Set.of(SIMULATED_PSP);

    private Providers() {
    }

    /** Every provider code this deployment accepts, for error messages and tests. */
    public static Set<String> known() {
        return new LinkedHashSet<>(KNOWN);
    }

    public static boolean isKnown(String provider) {
        return provider != null && KNOWN.contains(provider);
    }

    /**
     * Resolves the provider a request asked for, or fails.
     *
     * <p>An absent provider is not an error — it means the caller did not care, and the default is
     * the right answer. A <i>wrong</i> one is an error, because the caller believes they named
     * something real.
     *
     * @throws DomainException {@code 400 VALIDATION_FAILED} if the provider is not one we know
     */
    public static String resolve(String requested) {
        if (requested == null || requested.isBlank()) {
            return SIMULATED_PSP;
        }
        if (!isKnown(requested)) {
            throw new DomainException(ProblemCode.VALIDATION_FAILED,
                    "unknown provider '" + requested + "'; this deployment accepts "
                            + String.join(", ", known()));
        }
        return requested;
    }
}