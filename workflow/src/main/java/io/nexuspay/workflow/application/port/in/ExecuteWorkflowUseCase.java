package io.nexuspay.workflow.application.port.in;

import io.nexuspay.workflow.domain.ExecutionStatus;

import java.time.Instant;

/**
 * Use case for triggering and managing workflow executions.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
public interface ExecuteWorkflowUseCase {

    ExecutionResult triggerWorkflow(String workflowId, String tenantId, String triggerPayload);

    ExecutionResult getExecution(String executionId, String tenantId);

    void cancelExecution(String executionId, String tenantId);

    record ExecutionResult(
            String executionId,
            String workflowId,
            int workflowVersion,
            String temporalWorkflowId,
            ExecutionStatus status,
            String currentNodeId,
            String triggerPayload,
            String resultPayload,
            String failureReason,
            Instant startedAt,
            Instant completedAt
    ) {}
}
