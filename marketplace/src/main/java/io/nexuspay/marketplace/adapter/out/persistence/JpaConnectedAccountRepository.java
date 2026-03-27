package io.nexuspay.marketplace.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for connected accounts.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public interface JpaConnectedAccountRepository extends JpaRepository<ConnectedAccountEntity, String> {

    List<ConnectedAccountEntity> findByTenantId(String tenantId);
}
