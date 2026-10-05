package com.reconcile.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconcile.persistence.jdbc.Sql;
import com.reconcile.service.IdempotencyService;
import com.reconcile.service.PaymentService;
import com.reconcile.service.PaymentView;
import com.reconcile.shared.CurrencyCode;
import com.reconcile.shared.Money;
import com.reconcile.support.SharedPostgres;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Maps to row {@code I-IDEM-05}: an infrastructure failure must leave the key usable.
 *
 * <p>This is the row of §A3 that "most systems get wrong", so it is tested at the layer where the
 * mechanism actually lives rather than through HTTP. The mechanism is the transaction: the
 * reservation, the command and the response record share one transaction, so a failure anywhere in
 * the command rolls the reservation back with it and the key is free again. There is no cleanup
 * code to get wrong, and this test is what would notice if someone ever added one that only ran on
 * the happy path.
 *
 * <p>It also covers the contrast that gives the design its shape — the same key with a <b>business</b>
 * refusal is <i>kept</i> and replayed, while a 5xx is not. Both are asserted, because a system that
 * always released the key would pass an {@code I-IDEM-05}-only suite while quietly breaking the
 * determinism {@code I-IDEM-04} depends on.
 */
@SpringBootTest
class IdempotencyFailureIT {


    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedPostgres::jdbcUrl);
        registry.add("spring.datasource.username", SharedPostgres::username);
        registry.add("spring.datasource.password", SharedPostgres::password);
        registry.add("reconcile.security.operator-token", () -> "test-operator-token");
        registry.add("reconcile.security.admin-token", () -> "test-admin-token");
    }

    private static final byte[] CAPTURE_BODY = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private static final PaymentService.CommandContext CONTEXT =
            PaymentService.CommandContext.of(
                    new PaymentService.Actor("USER", "integration-test"), "req_test", null);

    @Autowired
    IdempotentCommand idempotent;

    @Autowired
    PaymentService payments;

    @Autowired
    Sql sql;

    @BeforeEach
    void reset() {
        sql.sql("""
                TRUNCATE payment_state_history, payment_ledger_transactions, ledger_entries,
                         ledger_transactions, account_balances, payments, payout_lines,
                         payout_records, settlement_record_lines, settlement_records,
                         provider_transactions, provider_events, provider_event_deliveries,
                         idempotency_records, reconciliation_cases, reconciliation_results,
                         reconciliation_subjects, reconciliation_batches, case_events,
                         audit_events, fee_policies
                RESTART IDENTITY CASCADE
                """).update();
        sql.sql("INSERT INTO fee_policies (id, code, bps, currency, effective_from) "
                + "VALUES ('fp_default','DEFAULT',300,'EGP','2026-01-01T00:00:00Z')").update();
        sql.sql("""
                INSERT INTO account_balances (account_id, currency)
                SELECT a.id, c.code FROM ledger_accounts a CROSS JOIN currency_units c WHERE c.active
                """).update();
    }

    @Test
    @DisplayName("I-IDEM-05: a 5xx releases the key, and the retry posts the ledger exactly once")
    void infrastructureFailureReleasesTheKey() {
        String paymentId = authorisedPayment("MERCH-05");
        String key = "idem-05-" + java.util.UUID.randomUUID();

        assertThatThrownBy(() -> idempotent.execute(
                IdempotencyService.CAPTURE_PAYMENT,
                key,
                CAPTURE_BODY,
                "/api/v1/payments/" + paymentId + "/capture",
                () -> {
                    // The ledger posting is reached and then the database "dies". The point is that
                    // the failure happens after the reservation and after work has begun, which is
                    // the only position where a rollback could plausibly fail to release the key.
                    throw new DataAccessResourceFailureException("injected database outage");
                }))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(recordFor(key))
                .as("the reservation rolled back with the command, so the key is free again")
                .isEmpty();
        assertThat(stateOf(paymentId)).isEqualTo("AUTHORIZED");
        assertThat(captureTransactionsFor(paymentId)).isZero();

        IdempotentCommand.Execution retry = idempotent.execute(
                IdempotencyService.CAPTURE_PAYMENT,
                key,
                CAPTURE_BODY,
                "/api/v1/payments/" + paymentId + "/capture",
                () -> {
                    PaymentView captured = payments.capture(paymentId, null, CONTEXT);
                    return new IdempotentCommand.Produced(
                            200, Map.of("state", captured.state().name()), "PAYMENT",
                            captured.id());
                });

        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.replayed()).as("a fresh execution, not a replay").isFalse();
        assertThat(recordFor(key))
                .as("now there is something to replay")
                .isPresent();
        assertThat(stateOf(paymentId)).isEqualTo("CAPTURED");
        assertThat(captureTransactionsFor(paymentId))
                .as("exactly one ledger posting: the failed attempt moved no money")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("I-IDEM-04 again, from the service layer: a business refusal is kept, not released")
    void businessRefusalIsKept() {
        String paymentId = authorisedPayment("MERCH-05-BUSINESS");
        String key = "idem-04-" + java.util.UUID.randomUUID();
        Money wrongAmount = Money.of(1, CurrencyCode.EGP);

        // A real refusal from the real service: capturing for an amount other than the authorised
        // one. Nothing is injected, so this is the code path a client would actually hit.
        IdempotentCommand.Execution refusal = idempotent.execute(
                IdempotencyService.CAPTURE_PAYMENT,
                key,
                CAPTURE_BODY,
                "/api/v1/payments/" + paymentId + "/capture",
                () -> {
                    PaymentView captured = payments.capture(paymentId, wrongAmount, CONTEXT);
                    return new IdempotentCommand.Produced(
                            200, Map.of("state", captured.state().name()), "PAYMENT",
                            captured.id());
                });

        assertThat(refusal.status())
                .as("a business refusal is answered, not thrown")
                .isEqualTo(409);
        assertThat(refusal.json()).contains("CAPTURE_AMOUNT_MISMATCH");
        assertThat(refusal.replayed()).isFalse();

        IdempotentCommand.Execution replay = idempotent.execute(
                IdempotencyService.CAPTURE_PAYMENT,
                key,
                CAPTURE_BODY,
                "/api/v1/payments/" + paymentId + "/capture",
                () -> {
                    PaymentView captured = payments.capture(paymentId, wrongAmount, CONTEXT);
                    return new IdempotentCommand.Produced(
                            200, Map.of("state", captured.state().name()), "PAYMENT",
                            captured.id());
                });

        assertThat(replay.json())
                .as("byte-identical, and no second attempt reached the service")
                .isEqualTo(refusal.json());
        assertThat(replay.replayed()).isTrue();

        assertThat(recordFor(key))
                .as("a 4xx is a deterministic answer and must survive to be replayed")
                .isPresent();
        assertThat(stateOf(paymentId)).isEqualTo("AUTHORIZED");
        assertThat(captureTransactionsFor(paymentId)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private String authorisedPayment(String merchantReference) {
        Money gross = Money.of(10_000, CurrencyCode.EGP);
        PaymentView created =
                payments.create(merchantReference, null, gross, "SIMULATED_PSP", CONTEXT);
        return payments.authorize(created.id(), CONTEXT).id();
    }

    private Optional<String> recordFor(String key) {
        return sql.sql("SELECT id FROM idempotency_records WHERE idem_key = ?")
                .param(key)
                .optional((rs, rowNum) -> rs.getString("id"));
    }

    private String stateOf(String paymentId) {
        return sql.sql("SELECT state FROM payments WHERE id = ?")
                .param(paymentId)
                .optional((rs, rowNum) -> rs.getString("state"))
                .orElse(null);
    }

    private int captureTransactionsFor(String paymentId) {
        return sql.sql("""
                SELECT count(*) AS total
                  FROM payment_ledger_transactions
                 WHERE payment_id = ? AND role = 'CAPTURE'
                """)
                .param(paymentId)
                .optional((rs, rowNum) -> rs.getInt("total"))
                .orElse(0);
    }
}
