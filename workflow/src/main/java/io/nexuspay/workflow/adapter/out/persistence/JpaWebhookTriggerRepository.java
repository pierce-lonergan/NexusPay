package io.nexuspay.workflow.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface JpaWebhookTriggerRepository extends JpaRepository<WebhookTriggerEntity, String> {
    Optional<WebhookTriggerEntity> findByUrlPath(String urlPath);

    // SEC-27: tenant-scoped by-id finder — the tenant predicate is pushed to SQL.
    Optional<WebhookTriggerEntity> findByIdAndTenantId(String id, String tenantId);
}
