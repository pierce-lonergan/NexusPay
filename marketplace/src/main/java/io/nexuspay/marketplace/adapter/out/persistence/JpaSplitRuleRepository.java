package io.nexuspay.marketplace.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for split rules.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public interface JpaSplitRuleRepository extends JpaRepository<SplitRuleEntity, String> {

    List<SplitRuleEntity> findBySplitPaymentId(String splitPaymentId);
}
