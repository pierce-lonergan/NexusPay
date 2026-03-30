package io.nexuspay.workflow.application.service;

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
        when(repository.findWorkflowById(wf.getId())).thenReturn(Optional.of(wf));
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
        when(repository.findWorkflowById("wf_missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.createTrigger("wf_missing", "tenant-1"));
    }

    @Test
    void deactivateTrigger_setsInactive() {
        WebhookTrigger trigger = WebhookTrigger.create("tenant-1", "wf_123");
        when(repository.findTriggerById(trigger.getId())).thenReturn(Optional.of(trigger));

        service.deactivateTrigger(trigger.getId(), "tenant-1");

        assertFalse(trigger.isActive());
        verify(repository).saveTrigger(trigger);
    }

    @Test
    void activateTrigger_setsActive() {
        WebhookTrigger trigger = WebhookTrigger.create("tenant-1", "wf_123");
        trigger.deactivate();
        when(repository.findTriggerById(trigger.getId())).thenReturn(Optional.of(trigger));

        service.activateTrigger(trigger.getId(), "tenant-1");

        assertTrue(trigger.isActive());
        verify(repository).saveTrigger(trigger);
    }

    @Test
    void regenerateSecret_producesNewSecret() {
        WebhookTrigger trigger = WebhookTrigger.create("tenant-1", "wf_123");
        String originalSecret = trigger.getSecret();
        when(repository.findTriggerById(trigger.getId())).thenReturn(Optional.of(trigger));
        when(repository.saveTrigger(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.regenerateSecret(trigger.getId(), "tenant-1");

        assertNotEquals(originalSecret, result.secret());
        assertNotNull(result.secret());
    }

    @Test
    void getTrigger_returnsResult() {
        WebhookTrigger trigger = WebhookTrigger.create("tenant-1", "wf_123");
        when(repository.findTriggerById(trigger.getId())).thenReturn(Optional.of(trigger));

        var result = service.getTrigger(trigger.getId(), "tenant-1");

        assertEquals(trigger.getId(), result.triggerId());
        assertEquals("wf_123", result.workflowId());
    }
}
