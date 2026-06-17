package io.nexuspay.workflow.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.tenant.TenantOwnership;
import io.nexuspay.workflow.application.port.in.ManageWorkflowUseCase;
import io.nexuspay.workflow.application.port.out.WorkflowBuilderRepository;
import io.nexuspay.workflow.application.port.out.WorkflowEventPublisher;
import io.nexuspay.workflow.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Service for workflow definition lifecycle management.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
@Service
public class WorkflowDefinitionService implements ManageWorkflowUseCase {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDefinitionService.class);

    private final WorkflowBuilderRepository repository;
    private final WorkflowEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public WorkflowDefinitionService(WorkflowBuilderRepository repository,
                                      WorkflowEventPublisher eventPublisher,
                                      ObjectMapper objectMapper) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public WorkflowResult createWorkflow(CreateWorkflowCommand command) {
        TriggerType triggerType = TriggerType.valueOf(command.triggerType());

        WorkflowDefinition wf = WorkflowDefinition.create(
                command.tenantId(), command.name(), command.description(),
                triggerType, command.createdBy());

        wf = repository.saveWorkflow(wf);

        eventPublisher.publishEvent("WorkflowDefinition", wf.getId(), "WorkflowCreated",
                Map.of("name", wf.getName(), "triggerType", triggerType.name(),
                        "tenantId", command.tenantId()),
                command.tenantId());

        log.info("Workflow created: id={}, name={}", wf.getId(), wf.getName());
        return toResult(wf);
    }

    @Override
    @Transactional(readOnly = true)
    public WorkflowResult getWorkflow(String workflowId, String tenantId) {
        return toResult(findOrThrow(workflowId, tenantId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowResult> listWorkflows(String tenantId) {
        return repository.findWorkflowsByTenantId(tenantId).stream()
                .map(this::toResult).toList();
    }

    @Override
    @Transactional
    public WorkflowResult updateWorkflow(String workflowId, String tenantId, UpdateWorkflowCommand command) {
        WorkflowDefinition wf = findOrThrow(workflowId, tenantId);

        if (command.name() != null) wf.setName(command.name());
        if (command.description() != null) wf.setDescription(command.description());
        if (command.triggerType() != null) wf.setTriggerType(TriggerType.valueOf(command.triggerType()));
        if (command.triggerConfig() != null) wf.setTriggerConfig(command.triggerConfig());

        wf = repository.saveWorkflow(wf);
        log.info("Workflow updated: id={}", workflowId);
        return toResult(wf);
    }

    @Override
    @Transactional
    public WorkflowResult publishWorkflow(String workflowId, String tenantId,
                                            String publishedBy, String changeDescription) {
        WorkflowDefinition wf = findOrThrow(workflowId, tenantId);
        wf.publish();
        wf = repository.saveWorkflow(wf);

        // Create version snapshot
        String graphSnapshot = serializeGraph(wf);
        WorkflowVersion version = WorkflowVersion.create(
                workflowId, wf.getVersion(), graphSnapshot, changeDescription, publishedBy);
        repository.saveVersion(version);

        eventPublisher.publishEvent("WorkflowDefinition", workflowId, "WorkflowPublished",
                Map.of("version", wf.getVersion(), "publishedBy", publishedBy,
                        "tenantId", tenantId),
                tenantId);

        log.info("Workflow published: id={}, version={}", workflowId, wf.getVersion());
        return toResult(wf);
    }

    @Override
    @Transactional
    public WorkflowResult archiveWorkflow(String workflowId, String tenantId) {
        WorkflowDefinition wf = findOrThrow(workflowId, tenantId);
        wf.archive();
        wf = repository.saveWorkflow(wf);

        eventPublisher.publishEvent("WorkflowDefinition", workflowId, "WorkflowArchived",
                Map.of("tenantId", tenantId), tenantId);

        log.info("Workflow archived: id={}", workflowId);
        return toResult(wf);
    }

    @Override
    @Transactional
    public WorkflowResult addNode(String workflowId, String tenantId, AddNodeCommand command) {
        WorkflowDefinition wf = findOrThrow(workflowId, tenantId);

        WorkflowNode node = WorkflowNode.create(
                NodeType.valueOf(command.nodeType()), command.label(), command.config(),
                command.positionX(), command.positionY());
        wf.addNode(node);

        wf = repository.saveWorkflow(wf);
        log.info("Node added to workflow: workflowId={}, nodeId={}, type={}", workflowId, node.getId(), command.nodeType());
        return toResult(wf);
    }

    @Override
    @Transactional
    public WorkflowResult removeNode(String workflowId, String tenantId, String nodeId) {
        WorkflowDefinition wf = findOrThrow(workflowId, tenantId);
        wf.removeNode(nodeId);
        wf = repository.saveWorkflow(wf);
        log.info("Node removed from workflow: workflowId={}, nodeId={}", workflowId, nodeId);
        return toResult(wf);
    }

    @Override
    @Transactional
    public WorkflowResult addEdge(String workflowId, String tenantId, AddEdgeCommand command) {
        WorkflowDefinition wf = findOrThrow(workflowId, tenantId);

        WorkflowEdge edge = WorkflowEdge.create(
                command.sourceNodeId(), command.targetNodeId(),
                command.conditionExpression(), command.label());
        wf.addEdge(edge);

        wf = repository.saveWorkflow(wf);
        log.info("Edge added to workflow: workflowId={}, edgeId={}", workflowId, edge.getId());
        return toResult(wf);
    }

    @Override
    @Transactional
    public WorkflowResult removeEdge(String workflowId, String tenantId, String edgeId) {
        WorkflowDefinition wf = findOrThrow(workflowId, tenantId);
        wf.removeEdge(edgeId);
        wf = repository.saveWorkflow(wf);
        log.info("Edge removed from workflow: workflowId={}, edgeId={}", workflowId, edgeId);
        return toResult(wf);
    }

    /**
     * SEC-27: tenant-scoped fetch-or-404. Every by-id read and mutation in this service routes through
     * here, pairing a tenant-scoped finder with {@link TenantOwnership#require} so a tenant-A caller
     * cannot read or mutate a tenant-B workflow by id. Absent OR foreign -> 404 (not 403), avoiding an
     * existence oracle. Previously the lookup used {@code findWorkflowById} (no tenant predicate — the
     * {@code tenantId} param was dead), the whole-module cross-tenant IDOR this fix closes.
     */
    private WorkflowDefinition findOrThrow(String workflowId, String tenantId) {
        return TenantOwnership.require(
                repository.findWorkflowByIdAndTenantId(workflowId, tenantId), "Workflow");
    }

    private String serializeGraph(WorkflowDefinition wf) {
        try {
            return objectMapper.writeValueAsString(Map.of("nodes", wf.getNodes(), "edges", wf.getEdges()));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize workflow graph", e);
        }
    }

    private WorkflowResult toResult(WorkflowDefinition wf) {
        List<NodeInfo> nodes = wf.getNodes() != null
                ? wf.getNodes().stream()
                    .map(n -> new NodeInfo(n.getId(), n.getNodeType().name(), n.getLabel(),
                            n.getConfig(), n.getPositionX(), n.getPositionY()))
                    .toList()
                : List.of();

        List<EdgeInfo> edges = wf.getEdges() != null
                ? wf.getEdges().stream()
                    .map(e -> new EdgeInfo(e.getId(), e.getSourceNodeId(), e.getTargetNodeId(),
                            e.getConditionExpression(), e.getLabel()))
                    .toList()
                : List.of();

        return new WorkflowResult(
                wf.getId(), wf.getName(), wf.getDescription(),
                wf.getStatus().name(), wf.getVersion(), wf.getTriggerType().name(),
                wf.getTriggerConfig(), nodes, edges, wf.getCreatedBy(),
                wf.getCreatedAt(), wf.getUpdatedAt());
    }
}
