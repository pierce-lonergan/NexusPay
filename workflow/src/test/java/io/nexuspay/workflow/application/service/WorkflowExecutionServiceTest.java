package io.nexuspay.workflow.application.service;

import io.nexuspay.common.exception.ResourceNotFoundException;
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
        when(repository.findWorkflowByIdAndTenantId(wf.getId(), "tenant-1")).thenReturn(Optional.of(wf));
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
        when(repository.findWorkflowByIdAndTenantId(wf.getId(), "tenant-1")).thenReturn(Optional.of(wf));

        assertThrows(IllegalStateException.class, () ->
                service.triggerWorkflow(wf.getId(), "tenant-1", "{}"));
    }

    @Test
    void triggerWorkflow_failsWhenNotFound() {
        when(repository.findWorkflowByIdAndTenantId("wf_missing", "tenant-1")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.triggerWorkflow("wf_missing", "tenant-1", "{}"));
    }

    @Test
    void triggerWorkflow_foreignTenant_throwsNotFound_andCreatesNoExecution() {
        // A tenant-A workflow exists, but tenant-B tries to trigger it by id. The scoped finder returns
        // empty -> 404, before any execution is recorded under the caller's tenant.
        WorkflowDefinition tenantAWorkflow =
                WorkflowDefinition.create("tenant-a", "Flow", null, TriggerType.WEBHOOK, "admin-a");
        when(repository.findWorkflowByIdAndTenantId(tenantAWorkflow.getId(), "tenant-b"))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.triggerWorkflow(tenantAWorkflow.getId(), "tenant-b", "{}"));

        verify(repository, never()).saveExecution(any());
        verify(repository, never()).findWorkflowById(any());
    }

    @Test
    void getExecution_returnsResult() {
        WorkflowExecution exec = WorkflowExecution.start("tenant-1", "wf_123", 1, "{\"data\":1}");
        exec.setTemporalWorkflowId("temporal_" + exec.getId());
        when(repository.findExecutionByIdAndTenantId(exec.getId(), "tenant-1")).thenReturn(Optional.of(exec));

        var result = service.getExecution(exec.getId(), "tenant-1");

        assertEquals(exec.getId(), result.executionId());
        assertEquals("wf_123", result.workflowId());
        assertEquals(ExecutionStatus.RUNNING, result.status());
    }

    @Test
    void getExecution_throwsWhenNotFound() {
        when(repository.findExecutionByIdAndTenantId("wex_missing", "tenant-1")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getExecution("wex_missing", "tenant-1"));
    }

    @Test
    void getExecution_foreignTenant_throwsNotFound_andQueriesByCallerTenant() {
        // A tenant-A execution exists, but tenant-B reads it by id -> scoped finder returns empty -> 404.
        WorkflowExecution tenantAExec = WorkflowExecution.start("tenant-a", "wf_123", 1, "{}");
        when(repository.findExecutionByIdAndTenantId(tenantAExec.getId(), "tenant-b"))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.getExecution(tenantAExec.getId(), "tenant-b"));

        verify(repository).findExecutionByIdAndTenantId(tenantAExec.getId(), "tenant-b");
        verify(repository, never()).findExecutionById(any());
    }

    @Test
    void cancelExecution_changesStatusToCancelled() {
        WorkflowExecution exec = WorkflowExecution.start("tenant-1", "wf_123", 1, "{}");
        when(repository.findExecutionByIdAndTenantId(exec.getId(), "tenant-1")).thenReturn(Optional.of(exec));

        service.cancelExecution(exec.getId(), "tenant-1");

        assertEquals(ExecutionStatus.CANCELLED, exec.getStatus());
        assertNotNull(exec.getCompletedAt());
        verify(repository).saveExecution(exec);
        verify(eventPublisher).publishEvent(eq("WorkflowExecution"), eq(exec.getId()), eq("WorkflowExecutionCancelled"), any(), eq("tenant-1"));
    }

    @Test
    void cancelExecution_foreignTenant_throwsNotFound_andDoesNotMutate() {
        WorkflowExecution tenantAExec = WorkflowExecution.start("tenant-a", "wf_123", 1, "{}");
        when(repository.findExecutionByIdAndTenantId(tenantAExec.getId(), "tenant-b"))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.cancelExecution(tenantAExec.getId(), "tenant-b"));

        verify(repository, never()).saveExecution(any());
    }
}
