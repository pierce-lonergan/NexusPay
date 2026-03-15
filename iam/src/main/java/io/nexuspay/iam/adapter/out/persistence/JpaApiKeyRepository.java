package io.nexuspay.iam.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JpaApiKeyRepository extends JpaRepository<ApiKeyEntity, String> {

    Optional<ApiKeyEntity> findByKeyPrefixAndRevokedAtIsNull(String keyPrefix);

    List<ApiKeyEntity> findAllByTenantIdAndRevokedAtIsNull(String tenantId);
}
