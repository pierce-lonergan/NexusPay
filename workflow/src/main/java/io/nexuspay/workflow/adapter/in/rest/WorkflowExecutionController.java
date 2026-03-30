package io.nexuspay.workflow.adapter.in.rest;

import io.nexuspay.workflow.adapter.in.rest.dto.ExecutionResponse;
import io.nexuspay.workflow.adapter.in.rest.dto.TriggerWorkflowRequest;
import io.nexuspay.workflow.application.port.in.ExecuteWorkflowUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for workflow execution management.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
@RestController
@RequestMapping("/v1/workflows")
public class WorkflowExecutionController {

    private final ExecuteWorkflowUseCase executeUseCase;

    public WorkflowExecutionController(ExecuteWorkflowUseCase executeUseCase) {
        this.executeUseCase = executeUseCase;
    }

    @PostMapping("/{workflowId}/trigger")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<ExecutionResponse> triggerWorkflow(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String workflowId,
            @Valid @RequestBody TriggerWorkflowRequest request) {

        var result = executeUseCase.triggerWorkflow(workflowId, tenantId, request.payload());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result));
    }

    @GetMapping("/executions/{executionId}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<ExecutionResponse> getExecution(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String executionId) {

        var result = executeUseCase.getExecution(executionId, tenantId);
        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/executions/{executionId}/cancel")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<Void> cancelExecution(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String executionId) {

        executeUseCase.cancelExecution(executionId, tenantId);
        return ResponseEntity.noContent().build();
    }

    private ExecutionResponse toResponse(ExecuteWorkflowUseCase.ExecutionResult result) {
        return new ExecutionResponse(
                result.executionId(), result.workflowId(), result.workflowVersion(),
                result.temporalWorkflowId(), result.status().name(), result.currentNodeId(),
                result.triggerPayload(), result.resultPayload(), result.failureReason(),
                result.startedAt(), result.completedAt());
    }
}
