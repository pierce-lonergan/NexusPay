package io.nexuspay.workflow.adapter.in.rest;

import io.nexuspay.workflow.adapter.in.rest.dto.*;
import io.nexuspay.workflow.application.port.in.ManageWorkflowUseCase;
import io.nexuspay.workflow.application.port.in.ManageWorkflowVersionUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for workflow definition management.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
@RestController
@RequestMapping("/v1/workflows")
public class WorkflowDefinitionController {

    private final ManageWorkflowUseCase workflowUseCase;
    private final ManageWorkflowVersionUseCase versionUseCase;

    public WorkflowDefinitionController(ManageWorkflowUseCase workflowUseCase,
                                          ManageWorkflowVersionUseCase versionUseCase) {
        this.workflowUseCase = workflowUseCase;
        this.versionUseCase = versionUseCase;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<WorkflowResponse> createWorkflow(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @Valid @RequestBody CreateWorkflowRequest request) {

        var result = workflowUseCase.createWorkflow(new ManageWorkflowUseCase.CreateWorkflowCommand(
                tenantId, request.name(), request.description(),
                request.triggerType(), request.createdBy()));

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result));
    }

    @GetMapping("/{workflowId}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<WorkflowResponse> getWorkflow(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String workflowId) {

        var result = workflowUseCase.getWorkflow(workflowId, tenantId);
        return ResponseEntity.ok(toResponse(result));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<List<WorkflowResponse>> listWorkflows(
            @RequestHeader("X-Tenant-Id") String tenantId) {

        var results = workflowUseCase.listWorkflows(tenantId);
        return ResponseEntity.ok(results.stream().map(this::toResponse).toList());
    }

    @PutMapping("/{workflowId}")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<WorkflowResponse> updateWorkflow(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String workflowId,
            @Valid @RequestBody UpdateWorkflowRequest request) {

        var result = workflowUseCase.updateWorkflow(workflowId, tenantId,
                new ManageWorkflowUseCase.UpdateWorkflowCommand(
                        request.name(), request.description(),
                        request.triggerType(), request.triggerConfig()));

        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/{workflowId}/publish")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<WorkflowResponse> publishWorkflow(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String workflowId,
            @Valid @RequestBody PublishWorkflowRequest request) {

        var result = workflowUseCase.publishWorkflow(workflowId, tenantId,
                request.publishedBy(), request.changeDescription());

        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/{workflowId}/archive")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<Void> archiveWorkflow(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String workflowId) {

        workflowUseCase.archiveWorkflow(workflowId, tenantId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{workflowId}/nodes")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<WorkflowResponse> addNode(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String workflowId,
            @Valid @RequestBody AddNodeRequest request) {

        var result = workflowUseCase.addNode(workflowId, tenantId,
                new ManageWorkflowUseCase.AddNodeCommand(
                        request.nodeType(), request.label(), request.config(),
                        request.positionX(), request.positionY()));

        return ResponseEntity.ok(toResponse(result));
    }

    @DeleteMapping("/{workflowId}/nodes/{nodeId}")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<WorkflowResponse> removeNode(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String workflowId,
            @PathVariable String nodeId) {

        var result = workflowUseCase.removeNode(workflowId, tenantId, nodeId);
        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/{workflowId}/edges")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<WorkflowResponse> addEdge(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String workflowId,
            @Valid @RequestBody AddEdgeRequest request) {

        var result = workflowUseCase.addEdge(workflowId, tenantId,
                new ManageWorkflowUseCase.AddEdgeCommand(
                        request.sourceNodeId(), request.targetNodeId(),
                        request.conditionExpression(), request.label()));

        return ResponseEntity.ok(toResponse(result));
    }

    @DeleteMapping("/{workflowId}/edges/{edgeId}")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<WorkflowResponse> removeEdge(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String workflowId,
            @PathVariable String edgeId) {

        var result = workflowUseCase.removeEdge(workflowId, tenantId, edgeId);
        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/{workflowId}/rollback")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<WorkflowResponse> rollbackWorkflow(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String workflowId,
            @Valid @RequestBody RollbackRequest request) {

        var result = versionUseCase.rollbackToVersion(workflowId, tenantId,
                request.targetVersion(), request.publishedBy());

        return ResponseEntity.ok(toResponse(result));
    }

    private WorkflowResponse toResponse(ManageWorkflowUseCase.WorkflowResult result) {
        List<WorkflowResponse.NodeDto> nodes = result.nodes().stream()
                .map(n -> new WorkflowResponse.NodeDto(n.nodeId(), n.nodeType(), n.label(),
                        n.config(), n.positionX(), n.positionY()))
                .toList();
        List<WorkflowResponse.EdgeDto> edges = result.edges().stream()
                .map(e -> new WorkflowResponse.EdgeDto(e.edgeId(), e.sourceNodeId(),
                        e.targetNodeId(), e.conditionExpression(), e.label()))
                .toList();

        return new WorkflowResponse(
                result.workflowId(), result.name(), result.description(),
                result.status(), result.version(), result.triggerType(),
                result.triggerConfig(), nodes, edges, result.createdBy(),
                result.createdAt(), result.updatedAt());
    }
}
