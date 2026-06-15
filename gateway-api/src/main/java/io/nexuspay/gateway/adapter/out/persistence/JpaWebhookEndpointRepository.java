package io.nexuspay.gateway.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JpaWebhookEndpointRepository extends JpaRepository<WebhookEndpointEntity, String> {

    List<WebhookEndpointEntity> findAllByTenantIdAndEnabledTrue(String tenantId);

    List<WebhookEndpointEntity> findAllByEnabledTrue();

    // SEC-19: tenant-scoped by-id lookup so delete cannot disable another tenant's endpoint.
    Optional<WebhookEndpointEntity> findByIdAndTenantId(String id, String tenantId);
}
