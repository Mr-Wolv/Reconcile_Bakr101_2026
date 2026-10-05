package com.reconcile.service;

import com.reconcile.config.ReconcileProperties;
import com.reconcile.persistence.jdbc.Sql;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The health check behind {@code GET /api/v1/health}.
 *
 * <p>Three checks, and the interesting one is the ledger (spec 06 §1):
 *
 * <ol>
 *   <li><b>migrations</b> — the schema is at the version this build expects and no migration
 *       failed. A half-applied migration is the one failure mode that leaves the process answering
 *       requests it should refuse, and nothing about it is otherwise visible.
 *   <li><b>ledger</b> — the L7 recomputation is clean. If the balance projection and the entries
 *       disagree, the system is not healthy whatever else is true, because every number it reports
 *       is derived from those balances.
 *   <li><b>reconciliation</b> — no batch has been {@code RUNNING} for longer than its lease. A
 *       batch that stopped without completing leaves a subject population frozen but never
 *       compared, and the operator has no way to tell from outside.
 * </ol>
 *
 * <p>The ledger check is a full recomputation on every health probe, which is deliberately
 * expensive. A health endpoint that returns {@code 200} without looking is the thing this project is
 * built against; the cost is the price of the answer, and the probe interval is the operator's
 * choice.
 *
 * <p>No repair happens here. A breach is reported, not corrected — silently rewriting a projection
 * destroys the evidence that something went wrong.
 */
@Service
public class HealthService {

    /**
     * The schema version this build was written against.
     *
     * <p>Bumped to {@code 2} alongside
     * {@code V2__financial_invariants_and_terminal_events.sql}, which added the header-side ledger
     * trigger, the settlement validity columns and the terminal {@code DEAD} event state.
     *
     * <p>Recording that here is the check working, not a nuisance: adding a migration without
     * bumping this constant makes {@code /health} report {@code DOWN} until it is bumped, which is
     * exactly the pairing the constant exists to force. The failure mode is a red health endpoint
     * on a perfectly healthy system, which is why it is worth the extra edit.
     */
    static final String EXPECTED_SCHEMA_VERSION = "2";

    private final Sql sql;
    private final LedgerService ledger;
    private final Clock clock;
    private final ReconcileProperties properties;

    public HealthService(Sql sql, LedgerService ledger, Clock clock, ReconcileProperties properties) {
        this.sql = sql;
        this.ledger = ledger;
        this.clock = clock;
        this.properties = properties;
    }

    /** One named check and what it found. */
    public record Check(String name, String status, String detail) {

        static Check pass(String name) {
            return new Check(name, "PASS", null);
        }

        static Check fail(String name, String detail) {
            return new Check(name, "FAIL", detail);
        }
    }

    /** The whole verdict. {@code status} is {@code UP} or {@code DOWN}. */
    public record Report(String status, List<Check> checks, Instant checkedAt) {

        public boolean healthy() {
            return "UP".equals(status);
        }
    }

    public Report check() {
        List<Check> checks = new ArrayList<>();
        checks.add(migrations());
        checks.add(ledgerBalances());
        checks.add(stuckBatches());

        boolean healthy = checks.stream().allMatch(c -> "PASS".equals(c.status()));
        return new Report(healthy ? "UP" : "DOWN", List.copyOf(checks), clock.instant());
    }

    private Check migrations() {
        // Absence of the table means Flyway has not run at all, which is a failure rather than a
        // pass: an app that migrated nothing must not advertise itself as healthy.
        // MAX() over an integer column yields an Integer, so this reads the object and stringifies
        // it rather than casting: a cast here is a ClassCastException inside a health check, which
        // is how a health endpoint ends up reporting 500 for a perfectly healthy system.
        var version = sql.sql("""
                SELECT MAX(CASE WHEN success THEN installed_rank END) AS version
                  FROM flyway_schema_history
                """)
                .optional((rs, rowNum) -> rs.getObject("version") == null
                        ? null
                        : String.valueOf(rs.getObject("version")))
                .orElse(null);

        boolean allSucceeded = sql.sql("""
                SELECT COUNT(*) AS failures FROM flyway_schema_history WHERE NOT success
                """).optional((rs, rowNum) -> rs.getLong("failures")).orElse(1L) == 0L;

        if (version == null) {
            return Check.fail("migrations", "flyway_schema_history is absent - no migration has run");
        }
        if (!EXPECTED_SCHEMA_VERSION.equals(version)) {
            return Check.fail("migrations",
                    "schema is at version " + version + ", this build expects "
                            + EXPECTED_SCHEMA_VERSION);
        }
        if (!allSucceeded) {
            return Check.fail("migrations", "a migration is recorded as unsuccessful");
        }
        return Check.pass("migrations");
    }

    private Check ledgerBalances() {
        List<LedgerService.BalanceDrift> drift = ledger.verifyBalances();
        if (drift.isEmpty()) {
            return Check.pass("ledger");
        }
        return Check.fail("ledger", "L7 verification failed for " + drift.size()
                + " account balance(s): " + drift.stream()
                        .map(d -> d.accountCode() + "/" + d.currency()
                                + (d.isMissingProjection()
                                        ? " has NO projection row but "
                                                + d.actualEntryCount() + " ledger entr"
                                                + (d.actualEntryCount() == 1 ? "y" : "ies")
                                                + " worth " + d.recomputedMinor()
                                        : " projected " + d.projectedMinor()
                                                + " but entries say " + d.recomputedMinor()))
                        .toList());
    }

    private Check stuckBatches() {
        long leaseMinutes = properties.reconciliation().lease().toMinutes();
        Instant cutoff = clock.instant().minus(leaseMinutes, ChronoUnit.MINUTES);

        List<String> stuck = sql.sql("""
                SELECT id FROM reconciliation_batches
                 WHERE status = 'RUNNING' AND started_at < ?
                 ORDER BY id
                """)
                .param(cutoff)
                .list((rs, rowNum) -> rs.getString("id"));

        if (stuck.isEmpty()) {
            return Check.pass("reconciliation");
        }
        return Check.fail("reconciliation", stuck.size()
                + " batch(es) have been RUNNING for longer than their " + leaseMinutes
                + " minute lease: " + stuck);
    }
}