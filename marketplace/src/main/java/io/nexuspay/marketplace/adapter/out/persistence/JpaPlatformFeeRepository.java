package io.nexuspay.marketplace.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA repository for platform fees.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public interface JpaPlatformFeeRepository extends JpaRepository<PlatformFeeEntity, String> {

    Optional<PlatformFeeEntity> findBySplitPaymentId(String splitPaymentId);
}
