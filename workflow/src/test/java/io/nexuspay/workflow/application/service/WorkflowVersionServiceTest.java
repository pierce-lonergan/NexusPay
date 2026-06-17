package io.nexuspay.workflow.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.exception.ResourceNotFoundException;
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
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WorkflowVersionService}.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
@ExtendWith(MockitoExtension.class)
class WorkflowVersionServiceTest {

    @Mock private WorkflowBuilderRepository repository;
    @Mock private WorkflowEventPublisher eventPublisher;

    private WorkflowVersionService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowVersionService(repository, eventPublisher, new ObjectMapper());
    }

    @Test
    void listVersions_returnsVersionHistory() {
        WorkflowDefinition wf = WorkflowDefinition.create("tenant-1", "Flow", null, TriggerType.WEBHOOK, "admin-1");
        var v1 = WorkflowVersion.create(wf.getId(), 1, "{}", "Initial", "admin-1");
        var v2 = WorkflowVersion.create(wf.getId(), 2, "{}", "Update nodes", "admin-1");
        when(repository.findWorkflowByIdAndTenantId(wf.getId(), "tenant-1")).thenReturn(Optional.of(wf));
        when(repository.findVersionsByWorkflowId(wf.getId())).thenReturn(List.of(v1, v2));

        var results = service.listVersions(wf.getId(), "tenant-1");

        assertEquals(2, results.size());
        assertEquals(1, results.get(0).versionNumber());
        assertEquals(2, results.get(1).versionNumber());
    }

    @Test
    void listVersions_foreignTenant_throwsNotFound_andDoesNotLeakHistory() {
        // Listing versions of a tenant-A workflow as tenant-B must 404 via the parent-workflow scope —
        // never reach the (unscoped) version-history query.
        WorkflowDefinition tenantAWorkflow =
                WorkflowDefinition.create("tenant-a", "Flow", null, TriggerType.WEBHOOK, "admin-a");
        when(repository.findWorkflowByIdAndTenantId(tenantAWorkflow.getId(), "tenant-b"))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.listVersions(tenantAWorkflow.getId(), "tenant-b"));

        verify(repository, never()).findVersionsByWorkflowId(any());
    }

    @Test
    void getVersion_returnsVersion() {
        var version = WorkflowVersion.create("wf_123", 1, "{}", "Initial", "admin-1");
        when(repository.findVersionByIdAndTenantId(version.getId(), "tenant-1")).thenReturn(Optional.of(version));

        var result = service.getVersion(version.getId(), "tenant-1");

        assertEquals(version.getId(), result.versionId());
        assertEquals("wf_123", result.workflowId());
        assertEquals("Initial", result.changeDescription());
    }

    @Test
    void getVersion_throwsWhenNotFound() {
        when(repository.findVersionByIdAndTenantId("wv_missing", "tenant-1")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getVersion("wv_missing", "tenant-1"));
    }

    @Test
    void getVersion_foreignTenant_throwsNotFound_andQueriesByCallerTenant() {
        // A version exists under tenant-A's workflow; tenant-B reads it by id. The scoped finder joins to
        // the parent workflow and filters on tenant, so it returns empty -> 404 (no oracle on the snapshot).
        var tenantAVersion = WorkflowVersion.create("wf_a", 1, "{}", "Initial", "admin-a");
        when(repository.findVersionByIdAndTenantId(tenantAVersion.getId(), "tenant-b"))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.getVersion(tenantAVersion.getId(), "tenant-b"));

        verify(repository).findVersionByIdAndTenantId(tenantAVersion.getId(), "tenant-b");
        verify(repository, never()).findVersionById(any());
    }

    @Test
    void rollbackToVersion_restoresGraphAndIncrementsVersion() {
        WorkflowDefinition wf = WorkflowDefinition.create("tenant-1", "Flow", null, TriggerType.WEBHOOK, "admin-1");
        wf.setVersion(3);
        String snapshot = "{\"nodes\":[{\"id\":\"nd_abc\",\"nodeType\":\"TRIGGER\",\"label\":\"Start\",\"config\":null,\"positionX\":0,\"positionY\":0}],\"edges\":[]}";
        WorkflowVersion targetVersion = WorkflowVersion.create(wf.getId(), 1, snapshot, "Initial", "admin-1");

        when(repository.findWorkflowByIdAndTenantId(wf.getId(), "tenant-1")).thenReturn(Optional.of(wf));
        when(repository.findVersionByWorkflowIdAndNumber(wf.getId(), 1)).thenReturn(Optional.of(targetVersion));
        when(repository.saveWorkflow(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.rollbackToVersion(wf.getId(), "tenant-1", 1, "admin-1");

        assertEquals(4, result.version()); // 3 + 1
        assertEquals(1, result.nodes().size());
        assertEquals("Start", result.nodes().get(0).label());

        verify(repository).saveVersion(any(WorkflowVersion.class));
        verify(eventPublisher).publishEvent(eq("WorkflowDefinition"), any(), eq("WorkflowRolledBack"), any(), eq("tenant-1"));
    }

    @Test
    void rollbackToVersion_throwsWhenWorkflowNotFound() {
        when(repository.findWorkflowByIdAndTenantId("wf_missing", "tenant-1")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.rollbackToVersion("wf_missing", "tenant-1", 1, "admin-1"));
    }

    @Test
    void rollbackToVersion_foreignTenant_throwsNotFound_andDoesNotMutate() {
        // A tenant-A admin's workflow; tenant-B attempts a rollback by id. The scoped workflow load
        // returns empty -> 404, so no graph is restored and nothing is written.
        WorkflowDefinition tenantAWorkflow =
                WorkflowDefinition.create("tenant-a", "Flow", null, TriggerType.WEBHOOK, "admin-a");
        when(repository.findWorkflowByIdAndTenantId(tenantAWorkflow.getId(), "tenant-b"))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.rollbackToVersion(tenantAWorkflow.getId(), "tenant-b", 1, "attacker"));

        verify(repository, never()).findVersionByWorkflowIdAndNumber(any(), org.mockito.ArgumentMatchers.anyInt());
        verify(repository, never()).saveWorkflow(any());
        verify(repository, never()).saveVersion(any());
    }
}
