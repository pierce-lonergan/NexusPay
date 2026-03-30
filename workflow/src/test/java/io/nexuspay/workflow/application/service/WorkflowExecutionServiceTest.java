package io.nexuspay.workflow.application.service;

import io.nexuspay.workflow.application.port.out.WorkflowBuilderRepository;
import io.nexuspay.workflow.application.port.out.WorkflowEventPublisher;
import io.nexuspay.workflow.domain.*;
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
 * Unit tests for {@link WorkflowExecutionService}.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
@ExtendWith(MockitoExtension.class)
class WorkflowExecutionServiceTest {

    @Mock private WorkflowBuilderRepository repository;
    @Mock private WorkflowEventPublisher eventPublisher;

    private WorkflowExecutionService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowExecutionService(repository, eventPublisher);
    }

    @Test
    void triggerWorkflow_happyPath() {
        WorkflowDefinition wf = WorkflowDefinition.create("tenant-1", "Flow", null, TriggerType.WEBHOOK, "admin-1");
        wf.addNode(WorkflowNode.create(NodeType.TRIGGER, "Start", null, 0, 0));
        wf.publish();
        when(repository.findWorkflowById(wf.getId())).thenReturn(Optional.of(wf));
        when(repository.saveExecution(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.triggerWorkflow(wf.getId(), "tenant-1", "{\"event\":\"test\"}");

        assertNotNull(result.executionId());
        assertTrue(result.executionId().startsWith("wex_"));
        assertEquals(wf.getId(), result.workflowId());
        assertEquals(ExecutionStatus.RUNNING, result.status());
        assertNotNull(result.temporalWorkflowId());
        assertTrue(result.temporalWorkflowId().startsWith("temporal_"));

        verify(repository).saveExecution(any());
        verify(eventPublisher).publishEvent(eq("WorkflowExecution"), any(), eq("WorkflowExecutionStarted"), any(), eq("tenant-1"));
    }

    @Test
    void triggerWorkflow_failsWhenNotPublished() {
        WorkflowDefinition wf = WorkflowDefinition.create("tenant-1", "Flow", null, TriggerType.MANUAL, "admin-1");
        when(repository.findWorkflowById(wf.getId())).thenReturn(Optional.of(wf));

        assertThrows(IllegalStateException.class, () ->
                service.triggerWorkflow(wf.getId(), "tenant-1", "{}"));
    }

    @Test
    void triggerWorkflow_failsWhenNotFound() {
        when(repository.findWorkflowById("wf_missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                service.triggerWorkflow("wf_missing", "tenant-1", "{}"));
    }

    @Test
    void getExecution_returnsResult() {
        WorkflowExecution exec = WorkflowExecution.start("tenant-1", "wf_123", 1, "{\"data\":1}");
        exec.setTemporalWorkflowId("temporal_" + exec.getId());
        when(repository.findExecutionById(exec.getId())).thenReturn(Optional.of(exec));

        var result = service.getExecution(exec.getId(), "tenant-1");

        assertEquals(exec.getId(), result.executionId());
        assertEquals("wf_123", result.workflowId());
        assertEquals(ExecutionStatus.RUNNING, result.status());
    }

    @Test
    void getExecution_throwsWhenNotFound() {
        when(repository.findExecutionById("wex_missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.getExecution("wex_missing", "tenant-1"));
    }

    @Test
    void cancelExecution_changesStatusToCancelled() {
        WorkflowExecution exec = WorkflowExecution.start("tenant-1", "wf_123", 1, "{}");
        when(repository.findExecutionById(exec.getId())).thenReturn(Optional.of(exec));

        service.cancelExecution(exec.getId(), "tenant-1");

        assertEquals(ExecutionStatus.CANCELLED, exec.getStatus());
        assertNotNull(exec.getCompletedAt());
        verify(repository).saveExecution(exec);
        verify(eventPublisher).publishEvent(eq("WorkflowExecution"), eq(exec.getId()), eq("WorkflowExecutionCancelled"), any(), eq("tenant-1"));
    }
}
