package io.nexuspay.gateway.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JpaWebhookEndpointRepository extends JpaRepository<WebhookEndpointEntity, String> {

    List<WebhookEndpointEntity> findAllByTenantIdAndEnabledTrue(String tenantId);

    List<WebhookEndpointEntity> findAllByEnabledTrue();
}
