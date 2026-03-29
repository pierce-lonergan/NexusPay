package io.nexuspay.workflow.application.port.in;

import io.nexuspay.workflow.domain.*;

import java.time.Instant;
import java.util.List;

/**
 * Use case for creating and managing workflow definitions.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
public interface ManageWorkflowUseCase {

    WorkflowResult createWorkflow(CreateWorkflowCommand command);

    WorkflowResult getWorkflow(String workflowId, String tenantId);

    List<WorkflowResult> listWorkflows(String tenantId);

    WorkflowResult updateWorkflow(String workflowId, String tenantId, UpdateWorkflowCommand command);

    WorkflowResult publishWorkflow(String workflowId, String tenantId, String publishedBy, String changeDescription);

    WorkflowResult archiveWorkflow(String workflowId, String tenantId);

    WorkflowResult addNode(String workflowId, String tenantId, AddNodeCommand command);

    WorkflowResult removeNode(String workflowId, String tenantId, String nodeId);

    WorkflowResult addEdge(String workflowId, String tenantId, AddEdgeCommand command);

    WorkflowResult removeEdge(String workflowId, String tenantId, String edgeId);

    record CreateWorkflowCommand(
            String tenantId,
            String name,
            String description,
            String triggerType,
            String createdBy
    ) {}

    record UpdateWorkflowCommand(
            String name,
            String description,
            String triggerType,
            String triggerConfig
    ) {}

    record AddNodeCommand(
            String nodeType,
            String label,
            String config,
            double positionX,
            double positionY
    ) {}

    record AddEdgeCommand(
            String sourceNodeId,
            String targetNodeId,
            String conditionExpression,
            String label
    ) {}

    record WorkflowResult(
            String workflowId,
            String name,
            String description,
            String status,
            int version,
            String triggerType,
            String triggerConfig,
            List<NodeInfo> nodes,
            List<EdgeInfo> edges,
            String createdBy,
            Instant createdAt,
            Instant updatedAt
    ) {}

    record NodeInfo(String nodeId, String nodeType, String label, String config,
                     double positionX, double positionY) {}

    record EdgeInfo(String edgeId, String sourceNodeId, String targetNodeId,
                     String conditionExpression, String label) {}
}
