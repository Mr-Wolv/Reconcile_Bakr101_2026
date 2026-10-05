package com.reconcile.persistence.repository;

import com.reconcile.persistence.entity.PaymentEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Read-side access to payments.
 *
 * <p>Listing is keyset-paginated on {@code (created_at DESC, id DESC)} rather than offset-paginated.
 * Offset pagination on a table that grows forever is a bug whose cost rises exactly when the system
 * is busiest: {@code OFFSET 100000} makes PostgreSQL produce and discard 100,000 rows, and two
 * concurrent inserts can make the same row appear on two consecutive pages. Keyset has neither
 * problem, and the composite index {@code ix_payments_state_created} serves it directly.
 */
public interface PaymentRepository extends JpaRepository<PaymentEntity, String> {

    /**
     * One page of payments, newest first.
     *
     * @param cursorInstant {@code created_at} of the last row of the previous page, or null
     * @param cursorId      {@code id} of the last row of the previous page, or null
     */
    @Query(value = """
            SELECT * FROM payments
             WHERE (:state IS NULL OR state = :state)
               AND (:merchantReference IS NULL OR merchant_reference = :merchantReference)
               AND (:providerTransactionId IS NULL OR provider_transaction_id = :providerTransactionId)
               AND (CAST(:cursorInstant AS timestamptz) IS NULL
                  OR (created_at, id) < (CAST(:cursorInstant AS timestamptz), :cursorId))
             ORDER BY created_at DESC, id DESC
             LIMIT :limit
            """,
            nativeQuery = true)
    List<PaymentEntity> page(
            @Param("state") String state,
            @Param("merchantReference") String merchantReference,
            @Param("providerTransactionId") String providerTransactionId,
            @Param("cursorInstant") Instant cursorInstant,
            @Param("cursorId") String cursorId,
            @Param("limit") int limit);
}