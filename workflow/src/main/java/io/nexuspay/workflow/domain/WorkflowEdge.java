package io.nexuspay.workflow.domain;

import java.util.UUID;

/**
 * Domain model representing a directed edge between two workflow nodes.
 * Edges may carry a condition expression for conditional branching.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
public class WorkflowEdge {

    private String id;
    private String sourceNodeId;
    private String targetNodeId;
    private String conditionExpression;
    private String label;

    public static WorkflowEdge create(String sourceNodeId, String targetNodeId,
                                       String conditionExpression, String label) {
        WorkflowEdge edge = new WorkflowEdge();
        edge.id = "eg_" + UUID.randomUUID().toString().replace("-", "");
        edge.sourceNodeId = sourceNodeId;
        edge.targetNodeId = targetNodeId;
        edge.conditionExpression = conditionExpression;
        edge.label = label;
        return edge;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSourceNodeId() { return sourceNodeId; }
    public void setSourceNodeId(String sourceNodeId) { this.sourceNodeId = sourceNodeId; }

    public String getTargetNodeId() { return targetNodeId; }
    public void setTargetNodeId(String targetNodeId) { this.targetNodeId = targetNodeId; }

    public String getConditionExpression() { return conditionExpression; }
    public void setConditionExpression(String conditionExpression) { this.conditionExpression = conditionExpression; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
}
