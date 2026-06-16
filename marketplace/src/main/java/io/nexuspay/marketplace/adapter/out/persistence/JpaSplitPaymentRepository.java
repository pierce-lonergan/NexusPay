package io.nexuspay.marketplace.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for split payments.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public interface JpaSplitPaymentRepository extends JpaRepository<SplitPaymentEntity, String> {

    List<SplitPaymentEntity> findByPaymentId(String paymentId);

    // SEC-BATCH-1: tenant-scoped by-id lookup.
    Optional<SplitPaymentEntity> findByIdAndTenantId(String id, String tenantId);

    // SEC-20: idempotency lookup. (tenant_id, payment_id) is UNIQUE (V4034), so at most one row matches.
    Optional<SplitPaymentEntity> findByTenantIdAndPaymentId(String tenantId, String paymentId);
}
