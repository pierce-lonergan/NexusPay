package io.nexuspay.workflow.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for the {@code workflow_versions} table.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
@Entity
@Table(name = "workflow_versions")
public class WorkflowVersionEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "workflow_id", nullable = false, length = 64)
    private String workflowId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "graph_snapshot", columnDefinition = "jsonb", nullable = false)
    private String graphSnapshot;

    @Column(name = "change_description", columnDefinition = "TEXT")
    private String changeDescription;

    @Column(name = "published_by", length = 128)
    private String publishedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

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
