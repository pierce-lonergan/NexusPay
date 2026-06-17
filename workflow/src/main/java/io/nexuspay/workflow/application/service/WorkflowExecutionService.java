package io.nexuspay.workflow.application.service;

import io.nexuspay.common.tenant.TenantOwnership;
import io.nexuspay.workflow.application.port.in.ExecuteWorkflowUseCase;
import io.nexuspay.workflow.application.port.out.WorkflowBuilderRepository;
import io.nexuspay.workflow.application.port.out.WorkflowEventPublisher;
import io.nexuspay.workflow.domain.WorkflowDefinition;
import io.nexuspay.workflow.domain.WorkflowExecution;
import io.nexuspay.workflow.domain.WorkflowStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Service for triggering and managing workflow executions.
 * Compiles the visual workflow graph and dispatches to Temporal for durable execution.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
@Service
public class WorkflowExecutionService implements ExecuteWorkflowUseCase {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutionService.class);

    private final WorkflowBuilderRepository repository;
    private final WorkflowEventPublisher eventPublisher;

    public WorkflowExecutionService(WorkflowBuilderRepository repository,
                                     WorkflowEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public ExecutionResult triggerWorkflow(String workflowId, String tenantId, String triggerPayload) {
        // SEC-27: scope the workflow lookup to the caller's tenant — a tenant-A caller cannot trigger a
        // tenant-B workflow by id. Absent OR foreign -> 404 (no existence oracle), before any execution
        // is created under the caller's tenant.
        WorkflowDefinition wf = TenantOwnership.require(
                repository.findWorkflowByIdAndTenantId(workflowId, tenantId), "Workflow");

        if (wf.getStatus() != WorkflowStatus.PUBLISHED) {
            throw new IllegalStateException("Can only trigger PUBLISHED workflows, current: " + wf.getStatus());
        }

        WorkflowExecution execution = WorkflowExecution.start(tenantId, workflowId, wf.getVersion(), triggerPayload);

        // TODO (GAP-071): Compile graph to Temporal workflow and dispatch.
        // For now, record execution with a placeholder Temporal workflow ID.
        execution.setTemporalWorkflowId("temporal_" + execution.getId());

        execution = repository.saveExecution(execution);

        eventPublisher.publishEvent("WorkflowExecution", execution.getId(), "WorkflowExecutionStarted",
                Map.of("workflowId", workflowId, "version", wf.getVersion(),
                        "tenantId", tenantId),
                tenantId);

        log.info("Workflow execution started: executionId={}, workflowId={}, version={}",
                execution.getId(), workflowId, wf.getVersion());

        return toResult(execution);
    }

    @Override
    @Transactional(readOnly = true)
    public ExecutionResult getExecution(String executionId, String tenantId) {
        return toResult(findOrThrow(executionId, tenantId));
    }

    @Override
    @Transactional
    public void cancelExecution(String executionId, String tenantId) {
        WorkflowExecution execution = findOrThrow(executionId, tenantId);
        execution.cancel();
        repository.saveExecution(execution);

        eventPublisher.publishEvent("WorkflowExecution", executionId, "WorkflowExecutionCancelled",
                Map.of("tenantId", tenantId), tenantId);

        log.info("Workflow execution cancelled: executionId={}", executionId);
    }

    /**
     * SEC-27: tenant-scoped fetch-or-404 for an execution. By-id read (getExecution) and mutation
     * (cancelExecution) route through here so a tenant-A caller cannot read or cancel a tenant-B
     * execution by id. Absent OR foreign -> 404 (no existence oracle).
     */
    private WorkflowExecution findOrThrow(String executionId, String tenantId) {
        return TenantOwnership.require(
                repository.findExecutionByIdAndTenantId(executionId, tenantId), "Execution");
    }

    private ExecutionResult toResult(WorkflowExecution ex) {
        return new ExecutionResult(
                ex.getId(), ex.getWorkflowId(), ex.getWorkflowVersion(),
                ex.getTemporalWorkflowId(), ex.getStatus(), ex.getCurrentNodeId(),
                ex.getTriggerPayload(), ex.getResultPayload(), ex.getFailureReason(),
                ex.getStartedAt(), ex.getCompletedAt());
    }
}
