package io.nexuspay.workflow.application.service;

import io.nexuspay.workflow.application.port.in.ManageWebhookTriggerUseCase;
import io.nexuspay.workflow.application.port.out.WorkflowBuilderRepository;
import io.nexuspay.workflow.application.port.out.WorkflowEventPublisher;
import io.nexuspay.workflow.domain.WebhookTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Service for managing inbound webhook triggers for workflows.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
@Service
public class WebhookTriggerService implements ManageWebhookTriggerUseCase {

    private static final Logger log = LoggerFactory.getLogger(WebhookTriggerService.class);

    private final WorkflowBuilderRepository repository;
    private final WorkflowEventPublisher eventPublisher;

    public WebhookTriggerService(WorkflowBuilderRepository repository,
                                   WorkflowEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public WebhookTriggerResult createTrigger(String workflowId, String tenantId) {
        // Verify workflow exists
        repository.findWorkflowById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowId));

        WebhookTrigger trigger = WebhookTrigger.create(tenantId, workflowId);
        trigger = repository.saveTrigger(trigger);

        eventPublisher.publishEvent("WebhookTrigger", trigger.getId(), "WebhookTriggerCreated",
                Map.of("workflowId", workflowId, "urlPath", trigger.getUrlPath(),
                        "tenantId", tenantId),
                tenantId);

        log.info("Webhook trigger created: id={}, workflowId={}, urlPath={}",
                trigger.getId(), workflowId, trigger.getUrlPath());

        return toResult(trigger);
    }

    @Override
    @Transactional(readOnly = true)
    public WebhookTriggerResult getTrigger(String triggerId, String tenantId) {
        return toResult(findOrThrow(triggerId));
    }

    @Override
    @Transactional
    public void deactivateTrigger(String triggerId, String tenantId) {
        WebhookTrigger trigger = findOrThrow(triggerId);
        trigger.deactivate();
        repository.saveTrigger(trigger);
        log.info("Webhook trigger deactivated: id={}", triggerId);
    }

    @Override
    @Transactional
    public void activateTrigger(String triggerId, String tenantId) {
        WebhookTrigger trigger = findOrThrow(triggerId);
        trigger.activate();
        repository.saveTrigger(trigger);
        log.info("Webhook trigger activated: id={}", triggerId);
    }

    @Override
    @Transactional
    public WebhookTriggerResult regenerateSecret(String triggerId, String tenantId) {
        WebhookTrigger trigger = findOrThrow(triggerId);
        trigger.regenerateSecret();
        trigger = repository.saveTrigger(trigger);
        log.info("Webhook trigger secret regenerated: id={}", triggerId);
        return toResult(trigger);
    }

    private WebhookTrigger findOrThrow(String triggerId) {
        return repository.findTriggerById(triggerId)
                .orElseThrow(() -> new IllegalArgumentException("Webhook trigger not found: " + triggerId));
    }

    private WebhookTriggerResult toResult(WebhookTrigger t) {
        return new WebhookTriggerResult(
                t.getId(), t.getWorkflowId(), t.getUrlPath(),
                t.getSecret(), t.isActive(), t.getCreatedAt());
    }
}
