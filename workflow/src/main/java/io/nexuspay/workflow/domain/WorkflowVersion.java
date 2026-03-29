package io.nexuspay.workflow.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain model representing a snapshot of a workflow at a point in time.
 * Enables version history, rollback, and diff views.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
public class WorkflowVersion {

    private String id;
    private String workflowId;
    private int versionNumber;
    private String graphSnapshot;
    private String changeDescription;
    private String publishedBy;
    private Instant createdAt;

    public static WorkflowVersion create(String workflowId, int versionNumber,
                                           String graphSnapshot, String changeDescription,
                                           String publishedBy) {
        WorkflowVersion v = new WorkflowVersion();
        v.id = "wv_" + UUID.randomUUID().toString().replace("-", "");
        v.workflowId = workflowId;
        v.versionNumber = versionNumber;
        v.graphSnapshot = graphSnapshot;
        v.changeDescription = changeDescription;
        v.publishedBy = publishedBy;
        v.createdAt = Instant.now();
        return v;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }

    public int getVersionNumber() { return versionNumber; }
    public void setVersionNumber(int versionNumber) { this.versionNumber = versionNumber; }

    public String getGraphSnapshot() { return graphSnapshot; }
    public void setGraphSnapshot(String graphSnapshot) { this.graphSnapshot = graphSnapshot; }

    public String getChangeDescription() { return changeDescription; }
    public void setChangeDescription(String changeDescription) { this.changeDescription = changeDescription; }

    public String getPublishedBy() { return publishedBy; }
    public void setPublishedBy(String publishedBy) { this.publishedBy = publishedBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
