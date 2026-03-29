package io.nexuspay.workflow.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for the {@code workflow_executions} table.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
@Entity
@Table(name = "workflow_executions")
public class WorkflowExecutionEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "workflow_id", nullable = false, length = 64)
    private String workflowId;

    @Column(name = "workflow_version", nullable = false)
    private int workflowVersion;

    @Column(name = "temporal_workflow_id", length = 255)
    private String temporalWorkflowId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "trigger_payload", columnDefinition = "jsonb")
    private String triggerPayload;

    @Column(name = "result_payload", columnDefinition = "jsonb")
    private String resultPayload;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "current_node_id", length = 64)
    private String currentNodeId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getWorkflowId() { return workflowId; }
    public void setWorkflowId(String workflowId) { this.workflowId = workflowId; }
    public int getWorkflowVersion() { return workflowVersion; }
    public void setWorkflowVersion(int workflowVersion) { this.workflowVersion = workflowVersion; }
    public String getTemporalWorkflowId() { return temporalWorkflowId; }
    public void setTemporalWorkflowId(String temporalWorkflowId) { this.temporalWorkflowId = temporalWorkflowId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTriggerPayload() { return triggerPayload; }
    public void setTriggerPayload(String triggerPayload) { this.triggerPayload = triggerPayload; }
    public String getResultPayload() { return resultPayload; }
    public void setResultPayload(String resultPayload) { this.resultPayload = resultPayload; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public String getCurrentNodeId() { return currentNodeId; }
    public void setCurrentNodeId(String currentNodeId) { this.currentNodeId = currentNodeId; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
