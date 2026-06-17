package io.nexuspay.workflow.adapter.in.rest;

import io.nexuspay.common.tenant.CallerTenant;
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
            @Valid @RequestBody CreateWorkflowRequest request) {

        // SEC-27: tenant resolved from the authenticated principal, never from a client X-Tenant-Id header.
        var result = workflowUseCase.createWorkflow(new ManageWorkflowUseCase.CreateWorkflowCommand(
                CallerTenant.require(), request.name(), request.description(),
                request.triggerType(), request.createdBy()));

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result));
    }

    @GetMapping("/{workflowId}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<WorkflowResponse> getWorkflow(
            @PathVariable String workflowId) {

        // SEC-27: by-id read scoped to the caller's tenant — a foreign-tenant id 404s (no oracle).
        var result = workflowUseCase.getWorkflow(workflowId, CallerTenant.require());
        return ResponseEntity.ok(toResponse(result));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<List<WorkflowResponse>> listWorkflows() {

        // SEC-27: tenant resolved from the authenticated principal, never from a client X-Tenant-Id header.
        var results = workflowUseCase.listWorkflows(CallerTenant.require());
        return ResponseEntity.ok(results.stream().map(this::toResponse).toList());
    }

    @PutMapping("/{workflowId}")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<WorkflowResponse> updateWorkflow(
            @PathVariable String workflowId,
            @Valid @RequestBody UpdateWorkflowRequest request) {

        // SEC-27: mutation scoped to the caller's tenant — a tenant-A caller cannot update a tenant-B workflow.
        var result = workflowUseCase.updateWorkflow(workflowId, CallerTenant.require(),
                new ManageWorkflowUseCase.UpdateWorkflowCommand(
                        request.name(), request.description(),
                        request.triggerType(), request.triggerConfig()));

        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/{workflowId}/publish")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<WorkflowResponse> publishWorkflow(
            @PathVariable String workflowId,
            @Valid @RequestBody PublishWorkflowRequest request) {

        // SEC-27: mutation scoped to the caller's tenant.
        var result = workflowUseCase.publishWorkflow(workflowId, CallerTenant.require(),
                request.publishedBy(), request.changeDescription());

        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/{workflowId}/archive")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<Void> archiveWorkflow(
            @PathVariable String workflowId) {

        // SEC-27: mutation scoped to the caller's tenant.
        workflowUseCase.archiveWorkflow(workflowId, CallerTenant.require());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{workflowId}/nodes")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<WorkflowResponse> addNode(
            @PathVariable String workflowId,
            @Valid @RequestBody AddNodeRequest request) {

        // SEC-27: mutation scoped to the caller's tenant.
        var result = workflowUseCase.addNode(workflowId, CallerTenant.require(),
                new ManageWorkflowUseCase.AddNodeCommand(
                        request.nodeType(), request.label(), request.config(),
                        request.positionX(), request.positionY()));

        return ResponseEntity.ok(toResponse(result));
    }

    @DeleteMapping("/{workflowId}/nodes/{nodeId}")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<WorkflowResponse> removeNode(
            @PathVariable String workflowId,
            @PathVariable String nodeId) {

        // SEC-27: mutation scoped to the caller's tenant.
        var result = workflowUseCase.removeNode(workflowId, CallerTenant.require(), nodeId);
        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/{workflowId}/edges")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<WorkflowResponse> addEdge(
            @PathVariable String workflowId,
            @Valid @RequestBody AddEdgeRequest request) {

        // SEC-27: mutation scoped to the caller's tenant.
        var result = workflowUseCase.addEdge(workflowId, CallerTenant.require(),
                new ManageWorkflowUseCase.AddEdgeCommand(
                        request.sourceNodeId(), request.targetNodeId(),
                        request.conditionExpression(), request.label()));

        return ResponseEntity.ok(toResponse(result));
    }

    @DeleteMapping("/{workflowId}/edges/{edgeId}")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<WorkflowResponse> removeEdge(
            @PathVariable String workflowId,
            @PathVariable String edgeId) {

        // SEC-27: mutation scoped to the caller's tenant.
        var result = workflowUseCase.removeEdge(workflowId, CallerTenant.require(), edgeId);
        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/{workflowId}/rollback")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<WorkflowResponse> rollbackWorkflow(
            @PathVariable String workflowId,
            @Valid @RequestBody RollbackRequest request) {

        // SEC-27: mutation scoped to the caller's tenant.
        var result = versionUseCase.rollbackToVersion(workflowId, CallerTenant.require(),
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
