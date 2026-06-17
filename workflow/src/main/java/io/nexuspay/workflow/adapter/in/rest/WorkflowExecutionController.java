package io.nexuspay.workflow.adapter.in.rest;

import io.nexuspay.common.tenant.CallerTenant;
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
            @PathVariable String workflowId,
            @Valid @RequestBody TriggerWorkflowRequest request) {

        // SEC-27: workflow lookup scoped to the caller's tenant — a foreign-tenant id 404s (no oracle).
        var result = executeUseCase.triggerWorkflow(workflowId, CallerTenant.require(), request.payload());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result));
    }

    @GetMapping("/executions/{executionId}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<ExecutionResponse> getExecution(
            @PathVariable String executionId) {

        // SEC-27: by-id read scoped to the caller's tenant — a foreign-tenant id 404s (no oracle).
        var result = executeUseCase.getExecution(executionId, CallerTenant.require());
        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/executions/{executionId}/cancel")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<Void> cancelExecution(
            @PathVariable String executionId) {

        // SEC-27: mutation scoped to the caller's tenant — a tenant-A caller cannot cancel a tenant-B execution.
        executeUseCase.cancelExecution(executionId, CallerTenant.require());
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
