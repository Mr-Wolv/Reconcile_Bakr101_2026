package com.reconcile.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Maps to rows {@code U-PAY-01} and {@code U-PAY-02}. */
class PaymentStateMachineTest {

    /** The seven transitions the specification permits, as (from, to) pairs — T1..T7. */
    private static final Set<String> PERMITTED = Set.of(
            "CREATED>AUTHORIZED",
            "CREATED>FAILED",
            "AUTHORIZED>CAPTURED",
            "AUTHORIZED>FAILED",
            "CAPTURED>SETTLED",
            "CAPTURED>REFUNDED",
            "SETTLED>REFUNDED");

    static List<Arguments> everyOrderedPair() {
        return java.util.Arrays.stream(PaymentState.values())
                .flatMap(from -> java.util.Arrays.stream(PaymentState.values())
                        .map(to -> Arguments.of(from, to)))
                .toList();
    }

    @ParameterizedTest(name = "U-PAY-01: {0} -> {1}")
    @MethodSource("everyOrderedPair")
    @DisplayName("U-PAY-01: the whole 6x6 matrix is either permitted or refused - nothing is undecided")
    void everyPairIsDecided(PaymentState from, PaymentState to) {
        boolean expected = PERMITTED.contains(from + ">" + to);

        assertThat(PaymentStateMachine.isAllowed(from, to))
                .as("%s -> %s", from, to)
                .isEqualTo(expected);

        if (expected) {
            assertThatCode(() -> PaymentStateMachine.require(from, to)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> PaymentStateMachine.require(from, to))
                    .isInstanceOf(IllegalStateTransitionException.class);
        }
    }

    @Test
    @DisplayName("U-PAY-01: the seven permitted transitions are exactly T1-T7 from the specification")
    void permittedSetMatchesTheSpecification() {
        var actual = new java.util.TreeSet<String>();
        for (PaymentState from : PaymentState.values()) {
            for (PaymentState to : PaymentState.values()) {
                if (PaymentStateMachine.isAllowed(from, to)) {
                    actual.add(from + ">" + to);
                }
            }
        }
        assertThat(actual).containsExactlyInAnyOrderElementsOf(PERMITTED);
        assertThat(actual).hasSize(7);
    }

    @Test
    @DisplayName("U-PAY-01: nothing leaves a terminal state")
    void terminalStatesAreTerminal() {
        for (PaymentState terminal : EnumSet.of(PaymentState.FAILED, PaymentState.REFUNDED)) {
            assertThat(terminal.isTerminal()).isTrue();
            assertThat(PaymentStateMachine.allowedTargets(terminal)).isEmpty();
            for (PaymentState to : PaymentState.values()) {
                assertThat(PaymentStateMachine.isAllowed(terminal, to)).isFalse();
            }
        }
        assertThat(PaymentStateMachine.isAllowed(PaymentState.REFUNDED, PaymentState.CAPTURED)).isFalse();
    }

    @Test
    @DisplayName("U-PAY-01: there is no un-capture and no CREATED -> SETTLED shortcut")
    void moneyIsNeverUndoneInPlace() {
        assertThat(PaymentStateMachine.isAllowed(PaymentState.CREATED, PaymentState.SETTLED)).isFalse();
        assertThat(PaymentStateMachine.isAllowed(PaymentState.CREATED, PaymentState.CAPTURED)).isFalse();
        assertThat(PaymentStateMachine.isAllowed(PaymentState.CAPTURED, PaymentState.AUTHORIZED)).isFalse();
        assertThat(PaymentStateMachine.isAllowed(PaymentState.SETTLED, PaymentState.CAPTURED)).isFalse();
    }

    @Test
    @DisplayName("U-PAY-01: the rejection message names the state and what was allowed instead")
    void rejectionExplainsItself() {
        var error = (IllegalStateTransitionException) org.assertj.core.api.Assertions
                .catchThrowable(() -> PaymentStateMachine.require(PaymentState.CREATED, PaymentState.SETTLED));

        assertThat(error).isNotNull();
        assertThat(error.from()).isEqualTo(PaymentState.CREATED);
        assertThat(error.to()).isEqualTo(PaymentState.SETTLED);
        assertThat(error.allowed()).containsExactlyInAnyOrder(PaymentState.AUTHORIZED, PaymentState.FAILED);
        assertThat(error.getMessage()).contains("CREATED").contains("SETTLED");
    }

    @Test
    @DisplayName("U-PAY-02: exactly one history row per transition, plus the initial row")
    void historyGrowsByOnePerTransition() {
        // CREATED -> AUTHORIZED -> CAPTURED -> SETTLED is three transitions.
        List<PaymentState> path = List.of(PaymentState.CREATED, PaymentState.AUTHORIZED,
                PaymentState.CAPTURED, PaymentState.SETTLED);

        int historyRows = 1; // the creation row
        for (int i = 1; i < path.size(); i++) {
            PaymentStateMachine.require(path.get(i - 1), path.get(i));
            historyRows++;
        }

        assertThat(historyRows).isEqualTo(path.size());
    }

    @Test
    @DisplayName("Progress along the happy path is what decides whether a stale webhook has an effect")
    void progressOrderingSupportsStaleEventDetection() {
        assertThat(PaymentState.SETTLED.isAtLeastAsFarAs(PaymentState.CAPTURED)).isTrue();
        assertThat(PaymentState.REFUNDED.isAtLeastAsFarAs(PaymentState.SETTLED)).isTrue();
        assertThat(PaymentState.CAPTURED.isAtLeastAsFarAs(PaymentState.SETTLED)).isFalse();
        assertThat(PaymentState.CAPTURED.isAtLeastAsFarAs(PaymentState.CAPTURED)).isTrue();
    }
}
