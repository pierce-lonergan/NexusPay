package io.nexuspay.marketplace.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for split payments.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public interface JpaSplitPaymentRepository extends JpaRepository<SplitPaymentEntity, String> {

    List<SplitPaymentEntity> findByPaymentId(String paymentId);
}
