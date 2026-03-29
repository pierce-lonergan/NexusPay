package io.nexuspay.workflow.application.port.in;

import java.time.Instant;

/**
 * Use case for managing inbound webhook triggers for workflows.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
public interface ManageWebhookTriggerUseCase {

    WebhookTriggerResult createTrigger(String workflowId, String tenantId);

    WebhookTriggerResult getTrigger(String triggerId, String tenantId);

    void deactivateTrigger(String triggerId, String tenantId);

    void activateTrigger(String triggerId, String tenantId);

    WebhookTriggerResult regenerateSecret(String triggerId, String tenantId);

    record WebhookTriggerResult(
            String triggerId,
            String workflowId,
            String urlPath,
            String secret,
            boolean active,
            Instant createdAt
    ) {}
}
