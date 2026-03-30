package io.nexuspay.workflow.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        var v1 = WorkflowVersion.create("wf_123", 1, "{}", "Initial", "admin-1");
        var v2 = WorkflowVersion.create("wf_123", 2, "{}", "Update nodes", "admin-1");
        when(repository.findVersionsByWorkflowId("wf_123")).thenReturn(List.of(v1, v2));

        var results = service.listVersions("wf_123", "tenant-1");

        assertEquals(2, results.size());
        assertEquals(1, results.get(0).versionNumber());
        assertEquals(2, results.get(1).versionNumber());
    }

    @Test
    void getVersion_returnsVersion() {
        var version = WorkflowVersion.create("wf_123", 1, "{}", "Initial", "admin-1");
        when(repository.findVersionById(version.getId())).thenReturn(Optional.of(version));

        var result = service.getVersion(version.getId(), "tenant-1");

        assertEquals(version.getId(), result.versionId());
        assertEquals("wf_123", result.workflowId());
        assertEquals("Initial", result.changeDescription());
    }

    @Test
    void getVersion_throwsWhenNotFound() {
        when(repository.findVersionById("wv_missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.getVersion("wv_missing", "tenant-1"));
    }

    @Test
    void rollbackToVersion_restoresGraphAndIncrementsVersion() {
        WorkflowDefinition wf = WorkflowDefinition.create("tenant-1", "Flow", null, TriggerType.WEBHOOK, "admin-1");
        wf.setVersion(3);
        String snapshot = "{\"nodes\":[{\"id\":\"nd_abc\",\"nodeType\":\"TRIGGER\",\"label\":\"Start\",\"config\":null,\"positionX\":0,\"positionY\":0}],\"edges\":[]}";
        WorkflowVersion targetVersion = WorkflowVersion.create(wf.getId(), 1, snapshot, "Initial", "admin-1");

        when(repository.findWorkflowById(wf.getId())).thenReturn(Optional.of(wf));
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
        when(repository.findWorkflowById("wf_missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                service.rollbackToVersion("wf_missing", "tenant-1", 1, "admin-1"));
    }
}
