package io.nexuspay.iam.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JpaApiKeyRepository extends JpaRepository<ApiKeyEntity, String> {

    List<ApiKeyEntity> findByKeyPrefixAndRevokedAtIsNull(String keyPrefix);

    List<ApiKeyEntity> findAllByTenantIdAndRevokedAtIsNull(String tenantId);
}
