package io.nexuspay.workflow.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Domain model representing a visual workflow definition.
 * A workflow is a directed acyclic graph of nodes connected by edges.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
public class WorkflowDefinition {

    private String id;
    private String tenantId;
    private String name;
    private String description;
    private WorkflowStatus status;
    private int version;
    private TriggerType triggerType;
    private String triggerConfig;
    private List<WorkflowNode> nodes;
    private List<WorkflowEdge> edges;
    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;

    public static WorkflowDefinition create(String tenantId, String name, String description,
                                              TriggerType triggerType, String createdBy) {
        WorkflowDefinition wf = new WorkflowDefinition();
        wf.id = "wf_" + UUID.randomUUID().toString().replace("-", "");
        wf.tenantId = tenantId;
        wf.name = name;
        wf.description = description;
        wf.status = WorkflowStatus.DRAFT;
        wf.version = 1;
        wf.triggerType = triggerType;
        wf.nodes = new ArrayList<>();
        wf.edges = new ArrayList<>();
        wf.createdAt = Instant.now();
        wf.updatedAt = Instant.now();
        wf.createdBy = createdBy;
        return wf;
    }

    public void addNode(WorkflowNode node) {
        this.nodes.add(node);
        this.updatedAt = Instant.now();
    }

    public void removeNode(String nodeId) {
        this.nodes.removeIf(n -> n.getId().equals(nodeId));
        this.edges.removeIf(e -> e.getSourceNodeId().equals(nodeId) || e.getTargetNodeId().equals(nodeId));
        this.updatedAt = Instant.now();
    }

    public void addEdge(WorkflowEdge edge) {
        this.edges.add(edge);
        this.updatedAt = Instant.now();
    }

    public void removeEdge(String edgeId) {
        this.edges.removeIf(e -> e.getId().equals(edgeId));
        this.updatedAt = Instant.now();
    }

    public void publish() {
        if (this.status == WorkflowStatus.ARCHIVED) {
            throw new IllegalStateException("Cannot publish ARCHIVED workflow");
        }
        if (this.nodes.isEmpty()) {
            throw new IllegalStateException("Cannot publish workflow with no nodes");
        }
        this.status = WorkflowStatus.PUBLISHED;
        this.updatedAt = Instant.now();
    }

    public void archive() {
        this.status = WorkflowStatus.ARCHIVED;
        this.updatedAt = Instant.now();
    }

    public void revertToDraft() {
        if (this.status != WorkflowStatus.PUBLISHED) {
            throw new IllegalStateException("Can only revert PUBLISHED workflows to DRAFT");
        }
        this.status = WorkflowStatus.DRAFT;
        this.updatedAt = Instant.now();
    }

    public WorkflowDefinition createNewVersion() {
        WorkflowDefinition copy = new WorkflowDefinition();
        copy.id = "wf_" + UUID.randomUUID().toString().replace("-", "");
        copy.tenantId = this.tenantId;
        copy.name = this.name;
        copy.description = this.description;
        copy.status = WorkflowStatus.DRAFT;
        copy.version = this.version + 1;
        copy.triggerType = this.triggerType;
        copy.triggerConfig = this.triggerConfig;
        copy.nodes = new ArrayList<>(this.nodes);
        copy.edges = new ArrayList<>(this.edges);
        copy.createdAt = Instant.now();
        copy.updatedAt = Instant.now();
        copy.createdBy = this.createdBy;
        return copy;
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; this.updatedAt = Instant.now(); }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; this.updatedAt = Instant.now(); }

    public WorkflowStatus getStatus() { return status; }
    public void setStatus(WorkflowStatus status) { this.status = status; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public TriggerType getTriggerType() { return triggerType; }
    public void setTriggerType(TriggerType triggerType) { this.triggerType = triggerType; }

    public String getTriggerConfig() { return triggerConfig; }
    public void setTriggerConfig(String triggerConfig) { this.triggerConfig = triggerConfig; }

    public List<WorkflowNode> getNodes() { return nodes; }
    public void setNodes(List<WorkflowNode> nodes) { this.nodes = nodes; }

    public List<WorkflowEdge> getEdges() { return edges; }
    public void setEdges(List<WorkflowEdge> edges) { this.edges = edges; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
