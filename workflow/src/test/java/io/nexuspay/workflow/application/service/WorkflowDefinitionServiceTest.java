package io.nexuspay.workflow.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.workflow.application.port.in.ManageWorkflowUseCase;
import io.nexuspay.workflow.application.port.out.WorkflowBuilderRepository;
import io.nexuspay.workflow.application.port.out.WorkflowEventPublisher;
import io.nexuspay.workflow.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WorkflowDefinitionService}.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
@ExtendWith(MockitoExtension.class)
class WorkflowDefinitionServiceTest {

    @Mock private WorkflowBuilderRepository repository;
    @Mock private WorkflowEventPublisher eventPublisher;

    private WorkflowDefinitionService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowDefinitionService(repository, eventPublisher, new ObjectMapper());
    }

    @Test
    void createWorkflow_happyPath() {
        when(repository.saveWorkflow(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.createWorkflow(new ManageWorkflowUseCase.CreateWorkflowCommand(
                "tenant-1", "Payment Flow", "A payment workflow", "WEBHOOK", "admin-1"));

        assertNotNull(result.workflowId());
        assertTrue(result.workflowId().startsWith("wf_"));
        assertEquals("Payment Flow", result.name());
        assertEquals("DRAFT", result.status());
        assertEquals(1, result.version());
        assertEquals("WEBHOOK", result.triggerType());
        assertEquals("admin-1", result.createdBy());

        verify(repository).saveWorkflow(any());
        verify(eventPublisher).publishEvent(eq("WorkflowDefinition"), any(), eq("WorkflowCreated"), any(), eq("tenant-1"));
    }

    @Test
    void getWorkflow_returnsResult() {
        WorkflowDefinition wf = WorkflowDefinition.create("tenant-1", "Test", "desc", TriggerType.MANUAL, "admin-1");
        when(repository.findWorkflowByIdAndTenantId(wf.getId(), "tenant-1")).thenReturn(Optional.of(wf));

        var result = service.getWorkflow(wf.getId(), "tenant-1");

        assertEquals(wf.getId(), result.workflowId());
        assertEquals("Test", result.name());
    }

    @Test
    void getWorkflow_throwsWhenNotFound() {
        when(repository.findWorkflowByIdAndTenantId("wf_missing", "tenant-1")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getWorkflow("wf_missing", "tenant-1"));
    }

    // ---------- SEC-27: cross-tenant isolation ----------

    @Test
    void getWorkflow_foreignTenant_throwsNotFound_andQueriesByCallerTenant() {
        // A tenant-A workflow exists, but tenant-B asks for it by id. The tenant-scoped finder returns
        // empty (no row for the caller's tenant) -> 404, not a 403 existence oracle.
        WorkflowDefinition tenantAWorkflow =
                WorkflowDefinition.create("tenant-a", "Secret Flow", "desc", TriggerType.MANUAL, "admin-a");
        when(repository.findWorkflowByIdAndTenantId(tenantAWorkflow.getId(), "tenant-b"))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.getWorkflow(tenantAWorkflow.getId(), "tenant-b"));

        verify(repository).findWorkflowByIdAndTenantId(tenantAWorkflow.getId(), "tenant-b");
        // The dead, unscoped finder (the IDOR) must never be used for the lookup.
        verify(repository, never()).findWorkflowById(any());
    }

    @Test
    void updateWorkflow_foreignTenant_throwsNotFound_andDoesNotMutate() {
        WorkflowDefinition tenantAWorkflow =
                WorkflowDefinition.create("tenant-a", "Old", "old", TriggerType.MANUAL, "admin-a");
        when(repository.findWorkflowByIdAndTenantId(tenantAWorkflow.getId(), "tenant-b"))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.updateWorkflow(
                tenantAWorkflow.getId(), "tenant-b",
                new ManageWorkflowUseCase.UpdateWorkflowCommand("Hijacked", null, null, null)));

        // No write may reach the repository for a foreign-tenant id.
        verify(repository, never()).saveWorkflow(any());
    }

    @Test
    void archiveWorkflow_foreignTenant_throwsNotFound_andDoesNotMutate() {
        WorkflowDefinition tenantAWorkflow =
                WorkflowDefinition.create("tenant-a", "Flow", null, TriggerType.WEBHOOK, "admin-a");
        when(repository.findWorkflowByIdAndTenantId(tenantAWorkflow.getId(), "tenant-b"))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.archiveWorkflow(tenantAWorkflow.getId(), "tenant-b"));

        verify(repository, never()).saveWorkflow(any());
    }

    @Test
    void listWorkflows_returnsList() {
        var wf1 = WorkflowDefinition.create("tenant-1", "Flow A", null, TriggerType.WEBHOOK, "admin-1");
        var wf2 = WorkflowDefinition.create("tenant-1", "Flow B", null, TriggerType.SCHEDULE, "admin-1");
        when(repository.findWorkflowsByTenantId("tenant-1")).thenReturn(List.of(wf1, wf2));

        var results = service.listWorkflows("tenant-1");

        assertEquals(2, results.size());
        assertEquals("Flow A", results.get(0).name());
        assertEquals("Flow B", results.get(1).name());
    }

    @Test
    void updateWorkflow_updatesFields() {
        WorkflowDefinition wf = WorkflowDefinition.create("tenant-1", "Old", "old desc", TriggerType.MANUAL, "admin-1");
        when(repository.findWorkflowByIdAndTenantId(wf.getId(), "tenant-1")).thenReturn(Optional.of(wf));
        when(repository.saveWorkflow(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.updateWorkflow(wf.getId(), "tenant-1",
                new ManageWorkflowUseCase.UpdateWorkflowCommand("New Name", "new desc", "WEBHOOK", null));

        assertEquals("New Name", result.name());
        assertEquals("new desc", result.description());
        assertEquals("WEBHOOK", result.triggerType());
    }

    @Test
    void publishWorkflow_changesStatusAndCreatesVersion() {
        WorkflowDefinition wf = WorkflowDefinition.create("tenant-1", "Flow", null, TriggerType.WEBHOOK, "admin-1");
        wf.addNode(WorkflowNode.create(NodeType.TRIGGER, "Start", null, 0, 0));
        when(repository.findWorkflowByIdAndTenantId(wf.getId(), "tenant-1")).thenReturn(Optional.of(wf));
        when(repository.saveWorkflow(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.publishWorkflow(wf.getId(), "tenant-1", "admin-1", "Initial publish");

        assertEquals("PUBLISHED", result.status());
        verify(repository).saveVersion(any(WorkflowVersion.class));
        verify(eventPublisher).publishEvent(eq("WorkflowDefinition"), any(), eq("WorkflowPublished"), any(), eq("tenant-1"));
    }

    @Test
    void publishWorkflow_failsWithNoNodes() {
        WorkflowDefinition wf = WorkflowDefinition.create("tenant-1", "Empty", null, TriggerType.MANUAL, "admin-1");
        when(repository.findWorkflowByIdAndTenantId(wf.getId(), "tenant-1")).thenReturn(Optional.of(wf));

        assertThrows(IllegalStateException.class, () ->
                service.publishWorkflow(wf.getId(), "tenant-1", "admin-1", "Should fail"));
    }

    @Test
    void archiveWorkflow_changesStatus() {
        WorkflowDefinition wf = WorkflowDefinition.create("tenant-1", "Flow", null, TriggerType.WEBHOOK, "admin-1");
        when(repository.findWorkflowByIdAndTenantId(wf.getId(), "tenant-1")).thenReturn(Optional.of(wf));
        when(repository.saveWorkflow(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.archiveWorkflow(wf.getId(), "tenant-1");

        assertEquals("ARCHIVED", result.status());
        verify(eventPublisher).publishEvent(eq("WorkflowDefinition"), any(), eq("WorkflowArchived"), any(), eq("tenant-1"));
    }

    @Test
    void addNode_addsToGraph() {
        WorkflowDefinition wf = WorkflowDefinition.create("tenant-1", "Flow", null, TriggerType.WEBHOOK, "admin-1");
        when(repository.findWorkflowByIdAndTenantId(wf.getId(), "tenant-1")).thenReturn(Optional.of(wf));
        when(repository.saveWorkflow(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.addNode(wf.getId(), "tenant-1",
                new ManageWorkflowUseCase.AddNodeCommand("PAYMENT", "Process Payment", "{}", 100.0, 200.0));

        assertEquals(1, result.nodes().size());
        assertEquals("PAYMENT", result.nodes().get(0).nodeType());
        assertEquals("Process Payment", result.nodes().get(0).label());
        assertEquals(100.0, result.nodes().get(0).positionX());
    }

    @Test
    void removeNode_removesNodeAndConnectedEdges() {
        WorkflowDefinition wf = WorkflowDefinition.create("tenant-1", "Flow", null, TriggerType.WEBHOOK, "admin-1");
        WorkflowNode node1 = WorkflowNode.create(NodeType.TRIGGER, "Start", null, 0, 0);
        WorkflowNode node2 = WorkflowNode.create(NodeType.PAYMENT, "Pay", null, 100, 100);
        wf.addNode(node1);
        wf.addNode(node2);
        WorkflowEdge edge = WorkflowEdge.create(node1.getId(), node2.getId(), null, "to payment");
        wf.addEdge(edge);

        when(repository.findWorkflowByIdAndTenantId(wf.getId(), "tenant-1")).thenReturn(Optional.of(wf));
        when(repository.saveWorkflow(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.removeNode(wf.getId(), "tenant-1", node1.getId());

        assertEquals(1, result.nodes().size());
        assertEquals(0, result.edges().size()); // Edge connected to removed node is also removed
    }

    @Test
    void addEdge_addsToGraph() {
        WorkflowDefinition wf = WorkflowDefinition.create("tenant-1", "Flow", null, TriggerType.WEBHOOK, "admin-1");
        WorkflowNode node1 = WorkflowNode.create(NodeType.TRIGGER, "Start", null, 0, 0);
        WorkflowNode node2 = WorkflowNode.create(NodeType.PAYMENT, "Pay", null, 100, 100);
        wf.addNode(node1);
        wf.addNode(node2);

        when(repository.findWorkflowByIdAndTenantId(wf.getId(), "tenant-1")).thenReturn(Optional.of(wf));
        when(repository.saveWorkflow(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.addEdge(wf.getId(), "tenant-1",
                new ManageWorkflowUseCase.AddEdgeCommand(node1.getId(), node2.getId(), null, "next"));

        assertEquals(1, result.edges().size());
        assertEquals(node1.getId(), result.edges().get(0).sourceNodeId());
        assertEquals(node2.getId(), result.edges().get(0).targetNodeId());
    }

    @Test
    void removeEdge_removesFromGraph() {
        WorkflowDefinition wf = WorkflowDefinition.create("tenant-1", "Flow", null, TriggerType.WEBHOOK, "admin-1");
        WorkflowNode node1 = WorkflowNode.create(NodeType.TRIGGER, "Start", null, 0, 0);
        WorkflowNode node2 = WorkflowNode.create(NodeType.END, "End", null, 200, 0);
        wf.addNode(node1);
        wf.addNode(node2);
        WorkflowEdge edge = WorkflowEdge.create(node1.getId(), node2.getId(), null, "finish");
        wf.addEdge(edge);

        when(repository.findWorkflowByIdAndTenantId(wf.getId(), "tenant-1")).thenReturn(Optional.of(wf));
        when(repository.saveWorkflow(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.removeEdge(wf.getId(), "tenant-1", edge.getId());

        assertEquals(0, result.edges().size());
        assertEquals(2, result.nodes().size());
    }
}
