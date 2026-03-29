package io.nexuspay.workflow.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface JpaWebhookTriggerRepository extends JpaRepository<WebhookTriggerEntity, String> {
    Optional<WebhookTriggerEntity> findByUrlPath(String urlPath);
}
