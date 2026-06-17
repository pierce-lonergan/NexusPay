package io.nexuspay.workflow.application.service;

import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.workflow.application.port.out.WorkflowBuilderRepository;
import io.nexuspay.workflow.application.port.out.WorkflowEventPublisher;
import io.nexuspay.workflow.domain.TriggerType;
import io.nexuspay.workflow.domain.WebhookTrigger;
import io.nexuspay.workflow.domain.WorkflowDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WebhookTriggerService}.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
@ExtendWith(MockitoExtension.class)
class WebhookTriggerServiceTest {

    @Mock private WorkflowBuilderRepository repository;
    @Mock private WorkflowEventPublisher eventPublisher;

    private WebhookTriggerService service;

    @BeforeEach
    void setUp() {
        service = new WebhookTriggerService(repository, eventPublisher);
    }

    @Test
    void createTrigger_happyPath() {
        WorkflowDefinition wf = WorkflowDefinition.create("tenant-1", "Flow", null, TriggerType.WEBHOOK, "admin-1");
        when(repository.findWorkflowByIdAndTenantId(wf.getId(), "tenant-1")).thenReturn(Optional.of(wf));
        when(repository.saveTrigger(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.createTrigger(wf.getId(), "tenant-1");

        assertNotNull(result.triggerId());
        assertTrue(result.triggerId().startsWith("wht_"));
        assertEquals(wf.getId(), result.workflowId());
        assertTrue(result.urlPath().startsWith("/webhooks/workflows/"));
        assertNotNull(result.secret());
        assertTrue(result.active());

        verify(eventPublisher).publishEvent(eq("WebhookTrigger"), any(), eq("WebhookTriggerCreated"), any(), eq("tenant-1"));
    }

    @Test
    void createTrigger_failsWhenWorkflowNotFound() {
        when(repository.findWorkflowByIdAndTenantId("wf_missing", "tenant-1")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.createTrigger("wf_missing", "tenant-1"));
    }

    @Test
    void createTrigger_foreignTenantWorkflow_throwsNotFound_andCreatesNoTrigger() {
        // A tenant-A workflow exists; tenant-B tries to attach a webhook to it by id. The scoped finder
        // returns empty -> 404, so no trigger is created against another tenant's workflow.
        WorkflowDefinition tenantAWorkflow =
                WorkflowDefinition.create("tenant-a", "Flow", null, TriggerType.WEBHOOK, "admin-a");
        when(repository.findWorkflowByIdAndTenantId(tenantAWorkflow.getId(), "tenant-b"))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.createTrigger(tenantAWorkflow.getId(), "tenant-b"));

        verify(repository, never()).saveTrigger(any());
        verify(repository, never()).findWorkflowById(any());
    }

    @Test
    void deactivateTrigger_setsInactive() {
        WebhookTrigger trigger = WebhookTrigger.create("tenant-1", "wf_123");
        when(repository.findTriggerByIdAndTenantId(trigger.getId(), "tenant-1")).thenReturn(Optional.of(trigger));

        service.deactivateTrigger(trigger.getId(), "tenant-1");

        assertFalse(trigger.isActive());
        verify(repository).saveTrigger(trigger);
    }

    @Test
    void activateTrigger_setsActive() {
        WebhookTrigger trigger = WebhookTrigger.create("tenant-1", "wf_123");
        trigger.deactivate();
        when(repository.findTriggerByIdAndTenantId(trigger.getId(), "tenant-1")).thenReturn(Optional.of(trigger));

        service.activateTrigger(trigger.getId(), "tenant-1");

        assertTrue(trigger.isActive());
        verify(repository).saveTrigger(trigger);
    }

    @Test
    void regenerateSecret_producesNewSecret() {
        WebhookTrigger trigger = WebhookTrigger.create("tenant-1", "wf_123");
        String originalSecret = trigger.getSecret();
        when(repository.findTriggerByIdAndTenantId(trigger.getId(), "tenant-1")).thenReturn(Optional.of(trigger));
        when(repository.saveTrigger(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.regenerateSecret(trigger.getId(), "tenant-1");

        assertNotEquals(originalSecret, result.secret());
        assertNotNull(result.secret());
    }

    @Test
    void getTrigger_returnsResult() {
        WebhookTrigger trigger = WebhookTrigger.create("tenant-1", "wf_123");
        when(repository.findTriggerByIdAndTenantId(trigger.getId(), "tenant-1")).thenReturn(Optional.of(trigger));

        var result = service.getTrigger(trigger.getId(), "tenant-1");

        assertEquals(trigger.getId(), result.triggerId());
        assertEquals("wf_123", result.workflowId());
    }

    @Test
    void getTrigger_foreignTenant_throwsNotFound_andDoesNotLeakSecret() {
        // A tenant-A trigger exists; tenant-B reads it by id. The scoped finder returns empty -> 404, so
        // another tenant's webhook secret is never disclosed.
        WebhookTrigger tenantATrigger = WebhookTrigger.create("tenant-a", "wf_a");
        when(repository.findTriggerByIdAndTenantId(tenantATrigger.getId(), "tenant-b"))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.getTrigger(tenantATrigger.getId(), "tenant-b"));

        verify(repository).findTriggerByIdAndTenantId(tenantATrigger.getId(), "tenant-b");
        verify(repository, never()).findTriggerById(any());
    }

    @Test
    void regenerateSecret_foreignTenant_throwsNotFound_andDoesNotMutate() {
        WebhookTrigger tenantATrigger = WebhookTrigger.create("tenant-a", "wf_a");
        when(repository.findTriggerByIdAndTenantId(tenantATrigger.getId(), "tenant-b"))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.regenerateSecret(tenantATrigger.getId(), "tenant-b"));

        verify(repository, never()).saveTrigger(any());
    }
}
