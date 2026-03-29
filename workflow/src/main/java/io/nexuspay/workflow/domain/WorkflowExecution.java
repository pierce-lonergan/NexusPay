package io.nexuspay.workflow.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain model representing a single execution of a workflow.
 * Tracks the lifecycle from start to completion/failure.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
public class WorkflowExecution {

    private String id;
    private String tenantId;
    private String workflowId;
    private int workflowVersion;
    private String temporalWorkflowId;
    private ExecutionStatus status;
    private String triggerPayload;
    private String resultPayload;
    private String failureReason;
    private String currentNodeId;
    private Instant startedAt;
    private Instant completedAt;

    public static WorkflowExecution start(String tenantId, String workflowId, int workflowVersion,
                                            String triggerPayload) {
        WorkflowExecution exec = new WorkflowExecution();
        exec.id = "wex_" + UUID.randomUUID().toString().replace("-", "");
        exec.tenantId = tenantId;
        exec.workflowId = workflowId;
        exec.workflowVersion = workflowVersion;
        exec.status = ExecutionStatus.RUNNING;
        exec.triggerPayload = triggerPayload;
        exec.startedAt = Instant.now();
        return exec;
    }

    public void complete(String resultPayload) {
        this.status = ExecutionStatus.COMPLETED;
        this.resultPayload = resultPayload;
        this.completedAt = Instant.now();
    }

    public void fail(String reason) {
        this.status = ExecutionStatus.FAILED;
        this.failureReason = reason;
        this.completedAt = Instant.now();
    }

    public void cancel() {
        this.status = ExecutionStatus.CANCELLED;
        this.completedAt = Instant.now();
    }

    public void timeout() {
        this.status = ExecutionStatus.TIMED_OUT;
        this.completedAt = Instant.now();
    }

    public void advanceToNode(String nodeId) {
        this.currentNodeId = nodeId;
    }

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

    public ExecutionStatus getStatus() { return status; }
    public void setStatus(ExecutionStatus status) { this.status = status; }

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
