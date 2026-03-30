package io.nexuspay.workflow.adapter.in.rest.dto;

import java.time.Instant;

public record ExecutionResponse(
        String executionId, String workflowId, int workflowVersion,
        String temporalWorkflowId, String status, String currentNodeId,
        String triggerPayload, String resultPayload, String failureReason,
        Instant startedAt, Instant completedAt
) {}
