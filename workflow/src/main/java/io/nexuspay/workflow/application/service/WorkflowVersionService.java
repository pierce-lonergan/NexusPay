package io.nexuspay.workflow.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.workflow.application.port.in.ManageWorkflowUseCase;
import io.nexuspay.workflow.application.port.in.ManageWorkflowVersionUseCase;
import io.nexuspay.workflow.application.port.out.WorkflowBuilderRepository;
import io.nexuspay.workflow.application.port.out.WorkflowEventPublisher;
import io.nexuspay.workflow.domain.WorkflowDefinition;
import io.nexuspay.workflow.domain.WorkflowEdge;
import io.nexuspay.workflow.domain.WorkflowNode;
import io.nexuspay.workflow.domain.WorkflowVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Service for workflow version history and rollback.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
@Service
public class WorkflowVersionService implements ManageWorkflowVersionUseCase {

    private static final Logger log = LoggerFactory.getLogger(WorkflowVersionService.class);

    private final WorkflowBuilderRepository repository;
    private final WorkflowEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public WorkflowVersionService(WorkflowBuilderRepository repository,
                                    WorkflowEventPublisher eventPublisher,
                                    ObjectMapper objectMapper) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<VersionInfo> listVersions(String workflowId, String tenantId) {
        return repository.findVersionsByWorkflowId(workflowId).stream()
                .map(this::toInfo).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public VersionInfo getVersion(String versionId, String tenantId) {
        return toInfo(repository.findVersionById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + versionId)));
    }

    @Override
    @Transactional
    public ManageWorkflowUseCase.WorkflowResult rollbackToVersion(String workflowId, String tenantId,
                                                                     int targetVersion, String publishedBy) {
        WorkflowDefinition wf = repository.findWorkflowById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + workflowId));

        WorkflowVersion version = repository.findVersionByWorkflowIdAndNumber(workflowId, targetVersion)
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + targetVersion));

        // Restore graph from snapshot
        restoreGraph(wf, version.getGraphSnapshot());
        wf.setVersion(wf.getVersion() + 1);

        wf = repository.saveWorkflow(wf);

        // Record rollback as new version
        WorkflowVersion rollbackVersion = WorkflowVersion.create(
                workflowId, wf.getVersion(), version.getGraphSnapshot(),
                "Rollback to version " + targetVersion, publishedBy);
        repository.saveVersion(rollbackVersion);

        eventPublisher.publishEvent("WorkflowDefinition", workflowId, "WorkflowRolledBack",
                Map.of("targetVersion", targetVersion, "newVersion", wf.getVersion(),
                        "tenantId", tenantId),
                tenantId);

        log.info("Workflow rolled back: id={}, from=v{}, to=v{}", workflowId, targetVersion, wf.getVersion());

        List<ManageWorkflowUseCase.NodeInfo> nodes = wf.getNodes().stream()
                .map(n -> new ManageWorkflowUseCase.NodeInfo(n.getId(), n.getNodeType().name(),
                        n.getLabel(), n.getConfig(), n.getPositionX(), n.getPositionY()))
                .toList();
        List<ManageWorkflowUseCase.EdgeInfo> edges = wf.getEdges().stream()
                .map(e -> new ManageWorkflowUseCase.EdgeInfo(e.getId(), e.getSourceNodeId(),
                        e.getTargetNodeId(), e.getConditionExpression(), e.getLabel()))
                .toList();

        return new ManageWorkflowUseCase.WorkflowResult(
                wf.getId(), wf.getName(), wf.getDescription(),
                wf.getStatus().name(), wf.getVersion(), wf.getTriggerType().name(),
                wf.getTriggerConfig(), nodes, edges, wf.getCreatedBy(),
                wf.getCreatedAt(), wf.getUpdatedAt());
    }

    private void restoreGraph(WorkflowDefinition wf, String graphSnapshot) {
        try {
            JsonNode root = objectMapper.readTree(graphSnapshot);
            List<WorkflowNode> nodes = objectMapper.readValue(
                    root.get("nodes").toString(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, WorkflowNode.class));
            List<WorkflowEdge> edges = objectMapper.readValue(
                    root.get("edges").toString(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, WorkflowEdge.class));
            wf.setNodes(nodes);
            wf.setEdges(edges);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to restore graph from snapshot", e);
        }
    }

    private VersionInfo toInfo(WorkflowVersion v) {
        return new VersionInfo(
                v.getId(), v.getWorkflowId(), v.getVersionNumber(),
                v.getGraphSnapshot(), v.getChangeDescription(),
                v.getPublishedBy(), v.getCreatedAt());
    }
}
