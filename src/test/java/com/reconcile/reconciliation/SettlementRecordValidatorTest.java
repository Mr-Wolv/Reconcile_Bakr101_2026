package com.reconcile.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconcile.provider.ProviderEventPayload;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Rows {@code U-SETTLE-01} … and the exact counterexamples behind F-01 and F-02.
 *
 * <p>Every case here is written as a record a real provider could plausibly send — including the
 * two the previous audit found in production traffic. They are pure-function tests on purpose: the
 * question "is this document fit to act on?" should be answerable without a database, and if it
 * cannot be, it has probably been folded into the code that moves money.
 */
class SettlementRecordValidatorTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 4);

    /**
     * Payment currencies by provider transaction id; absent means "we have no such payment".
     *
     * <p>Static so the {@code @Nested} groups below can reach it — an inner class does not inherit
     * the enclosing instance — and cleared before each test so no case can see another's payments.
     */
    private static final Map<String, String> payments = new HashMap<>();

    private static Optional<String> currencyOf(String providerTransactionId) {
        return Optional.ofNullable(payments.get(providerTransactionId));
    }

    @BeforeEach
    void clearPayments() {
        payments.clear();
    }

    private static ProviderEventPayload.Line line(String id, long gross, long net) {
        return new ProviderEventPayload.Line(id, gross, net);
    }

    /** A record whose totals are consistent with its lines, and which is therefore valid. */
    private static ProviderEventPayload.Settlement settlement(
            String currency, long gross, long fee, long net, ProviderEventPayload.Line... lines) {
        return new ProviderEventPayload.Settlement(
                "psr_1", DATE, currency, gross, fee, net, List.of(lines));
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    @DisplayName("A record that agrees with its lines and with the payment is VALID")
    void aCoherentRecordIsValid() {
        payments.put("psp_a", "EGP");

        var verdict = SettlementRecordValidator.validate(
                settlement("EGP", 10_000, 300, 9_700, line("psp_a", 10_000, 9_700)),
                SettlementRecordValidatorTest::currencyOf);

        assertThat(verdict.valid()).isTrue();
        assertThat(verdict.reason()).isNull();
        assertThat(verdict.validity()).isEqualTo(SettlementValidity.VALID);
    }

    @Test
    @DisplayName("A line for a payment we have never seen is not a violation of S9")
    void unknownPaymentsAreNotACurrencyProblem() {
        // There is no currency to disagree with, and inventing one would be inventing money. The
        // transaction is recorded provider-side and reconciliation reports it MISSING_INTERNAL,
        // which is the true finding.
        var verdict = SettlementRecordValidator.validate(
                settlement("USD", 10_000, 300, 9_700, line("psp_unknown", 10_000, 9_700)),
                SettlementRecordValidatorTest::currencyOf);

        assertThat(verdict.valid()).isTrue();
    }

    // ------------------------------------------------------------------ F-01: currency

    @Nested
    @DisplayName("F-01: currency agreement with the payments being settled")
    class Currency {

        @Test
        @DisplayName("F-01: a USD record settling an EGP payment is INVALID, and says why")
        void crossCurrencySettlementIsRefused() {
            payments.put("psp_a", "EGP");

            var verdict = SettlementRecordValidator.validate(
                    settlement("USD", 10_000, 300, 9_700, line("psp_a", 10_000, 9_700)),
                    SettlementRecordValidatorTest::currencyOf);

            assertThat(verdict.valid())
                    .as("a valid signature authenticates the sender, not the consistency of the "
                            + "document; v1 has no FX, so there is nothing to convert")
                    .isFalse();
            assertThat(verdict.reason())
                    .contains("USD")
                    .contains("EGP")
                    .contains("psp_a")
                    .contains("does not convert");
            assertThat(verdict.validity()).isEqualTo(SettlementValidity.INVALID);
        }

        @Test
        @DisplayName("F-01: one bad line among several is still enough to refuse the whole record")
        void oneWrongLineRefusesTheRecord() {
            payments.put("psp_a", "EGP");
            payments.put("psp_b", "USD");
            payments.put("psp_c", "EGP");

            var verdict = SettlementRecordValidator.validate(
                    settlement("EGP", 30_000, 900, 29_100,
                            line("psp_a", 10_000, 9_700),
                            line("psp_b", 10_000, 9_700),
                            line("psp_c", 10_000, 9_700)),
                    SettlementRecordValidatorTest::currencyOf);

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.reason()).contains("psp_b").contains("USD");
        }

        @Test
        @DisplayName("F-01: the currency check is reported before any aggregate difference")
        void currencyIsCheckedBeforeArithmetic() {
            // The record also has wrong totals. Reporting the arithmetic first would send an
            // operator to compare numbers that were never commensurable.
            payments.put("psp_a", "EGP");

            var verdict = SettlementRecordValidator.validate(
                    settlement("USD", 99_999, 1, 98_998, line("psp_a", 10_000, 9_700)),
                    SettlementRecordValidatorTest::currencyOf);

            assertThat(verdict.reason())
                    .contains("does not convert")
                    .doesNotContain("contradicts its own lines");
        }

        @Test
        @DisplayName("An unsupported currency is refused rather than failing a foreign key later")
        void unsupportedCurrencyIsRefused() {
            payments.put("psp_a", "EGP");

            var verdict = SettlementRecordValidator.validate(
                    settlement("XXX", 10_000, 300, 9_700, line("psp_a", 10_000, 9_700)),
                    SettlementRecordValidatorTest::currencyOf);

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.reason()).contains("XXX").contains("does not support");
        }
    }

    // ------------------------------------------------------------------ F-02: aggregates

    @Nested
    @DisplayName("F-02: the record's totals must agree with its own lines")
    class Aggregates {

        @Test
        @DisplayName("F-02: a net that disagrees with the lines is INVALID, even though gross matches")
        void netDisagreeingWithLinesIsRefused() {
            // The exact record the audit found: gross 10,000 / fee 400 / net 9,600 over one line of
            // gross 10,000 / net 9,700. The record satisfies its OWN split perfectly — 9,600 + 400 =
            // 10,000 — and is still 100 minor units wrong, because the line implies a fee of 300.
            payments.put("psp_a", "EGP");

            var verdict = SettlementRecordValidator.validate(
                    settlement("EGP", 10_000, 400, 9_600, line("psp_a", 10_000, 9_700)),
                    SettlementRecordValidatorTest::currencyOf);

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.reason())
                    .contains("contradicts its own lines")
                    .contains("net 9600")
                    .contains("lines total 9700")
                    .contains("fee 400")
                    .contains("imply 300");
        }

        @Test
        @DisplayName("F-02: a gross that disagrees with the lines is INVALID")
        void grossDisagreeingWithLinesIsRefused() {
            payments.put("psp_a", "EGP");

            var verdict = SettlementRecordValidator.validate(
                    settlement("EGP", 9_700, 300, 9_400, line("psp_a", 10_000, 9_700)),
                    SettlementRecordValidatorTest::currencyOf);

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.reason()).contains("gross 9700").contains("line(s) total 10000");
        }

        @Test
        @DisplayName("F-02: a fee that disagrees with the lines is INVALID")
        void feeDisagreeingWithLinesIsRefused() {
            // gross and net both agree; only the fee is wrong. Proves S4 is not redundant with
            // S2 and S3.
            payments.put("psp_a", "EGP");

            var verdict = SettlementRecordValidator.validate(
                    settlement("EGP", 10_000, 200, 9_800, line("psp_a", 10_000, 9_700)),
                    SettlementRecordValidatorTest::currencyOf);

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.reason()).contains("fee 200").contains("imply 300");
        }

        @Test
        @DisplayName("F-02: every wrong total is reported in one pass, not one field per delivery")
        void allAggregateProblemsAreReportedTogether() {
            payments.put("psp_a", "EGP");

            var verdict = SettlementRecordValidator.validate(
                    settlement("EGP", 1_000, 1, 999, line("psp_a", 10_000, 9_700)),
                    SettlementRecordValidatorTest::currencyOf);

            assertThat(verdict.reason())
                    .contains("gross")
                    .contains("net")
                    .contains("fee");
        }

        @Test
        @DisplayName("A record whose lines are internally consistent across several lines is VALID")
        void severalConsistentLinesAreValid() {
            payments.put("psp_a", "EGP");
            payments.put("psp_b", "EGP");

            var verdict = SettlementRecordValidator.validate(
                    settlement("EGP", 20_000, 600, 19_400,
                            line("psp_a", 10_000, 9_700),
                            line("psp_b", 10_000, 9_700)),
                    SettlementRecordValidatorTest::currencyOf);

            assertThat(verdict.valid()).isTrue();
        }

        @Test
        @DisplayName("S5: a line netting more than its gross is refused, not arithmetically accepted")
        void aLineCannotNetMoreThanItsGross() {
            // Deliberately built so the RECORD totals are all non-negative and consistent
            // (20,000 = 19,500 + 500), so the only thing wrong is one line. A single bad line paired
            // with a negative record fee would be caught by S7 first and would prove nothing about
            // S5 — the per-line rule earns its place only when the record itself looks fine.
            payments.put("psp_a", "EGP");
            payments.put("psp_b", "EGP");

            var verdict = SettlementRecordValidator.validate(
                    settlement("EGP", 20_000, 500, 19_500,
                            line("psp_a", 10_000, 10_500),
                            line("psp_b", 10_000, 9_000)),
                    SettlementRecordValidatorTest::currencyOf);

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.reason())
                    .contains("psp_a")
                    .contains("nets 10500 on a gross of only 10000")
                    .contains("derived fee would be negative")
                    .doesNotContain("contradicts its own lines");
        }
    }

    // ------------------------------------------------------------------ structural

    @Nested
    @DisplayName("Structure: a record that cannot describe a set of transactions")
    class Structure {

        @Test
        @DisplayName("S1: a record with no lines is refused rather than vacuously valid")
        void anEmptyRecordIsRefused() {
            // Every sum below is zero over an empty collection, so without S1 this record would pass
            // every arithmetic check while settling nothing.
            var verdict = SettlementRecordValidator.validate(
                    settlement("EGP", 0, 0, 0), SettlementRecordValidatorTest::currencyOf);

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.reason()).contains("no lines");
        }

        @Test
        @DisplayName("S6: the same transaction listed twice is refused before its totals are summed")
        void aDuplicatedLineIsRefused() {
            payments.put("psp_a", "EGP");

            var verdict = SettlementRecordValidator.validate(
                    settlement("EGP", 20_000, 600, 19_400,
                            line("psp_a", 10_000, 9_700),
                            line("psp_a", 10_000, 9_700)),
                    SettlementRecordValidatorTest::currencyOf);

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.reason())
                    .as("reported as a structural fault, not as a total that disagrees")
                    .contains("psp_a")
                    .contains("twice")
                    .doesNotContain("contradicts its own lines");
        }

        @Test
        @DisplayName("S7: a negative total is refused as itself, not as a difference it produces")
        void negativeAmountsAreRefused() {
            payments.put("psp_a", "EGP");

            var verdict = SettlementRecordValidator.validate(
                    settlement("EGP", -10_000, 300, 9_700, line("psp_a", 10_000, 9_700)),
                    SettlementRecordValidatorTest::currencyOf);

            assertThat(verdict.valid()).isFalse();
            assertThat(verdict.reason()).contains("negative total");
        }

        @Test
        @DisplayName("A record with no lines and no payments is refused, not vacuously valid")
        void nothingToSettleIsNotValid() {
            // The degenerate end of S1: every sum is zero, there is no payment to disagree with, and
            // the record still describes nothing. A validator that reported VALID here would let a
            // settlement with no lines settle nothing and post nothing while looking successful.
            var zeroLines = new ProviderEventPayload.Settlement(
                    "psr_1", DATE, "EGP", 0, 0, 0, List.of());

            assertThat(SettlementRecordValidator.validate(zeroLines, id -> Optional.empty()).valid())
                    .isFalse();
        }
    }
}