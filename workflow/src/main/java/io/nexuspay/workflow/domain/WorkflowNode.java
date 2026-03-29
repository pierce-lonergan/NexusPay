package io.nexuspay.workflow.domain;

import java.util.UUID;

/**
 * Domain model representing a single node in a workflow graph.
 * Each node has a type, position (for the visual canvas), and a JSON config.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
public class WorkflowNode {

    private String id;
    private NodeType nodeType;
    private String label;
    private String config;
    private double positionX;
    private double positionY;

    public static WorkflowNode create(NodeType nodeType, String label, String config,
                                       double positionX, double positionY) {
        WorkflowNode node = new WorkflowNode();
        node.id = "nd_" + UUID.randomUUID().toString().replace("-", "");
        node.nodeType = nodeType;
        node.label = label;
        node.config = config;
        node.positionX = positionX;
        node.positionY = positionY;
        return node;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public NodeType getNodeType() { return nodeType; }
    public void setNodeType(NodeType nodeType) { this.nodeType = nodeType; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getConfig() { return config; }
    public void setConfig(String config) { this.config = config; }

    public double getPositionX() { return positionX; }
    public void setPositionX(double positionX) { this.positionX = positionX; }

    public double getPositionY() { return positionY; }
    public void setPositionY(double positionY) { this.positionY = positionY; }
}
