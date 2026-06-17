package io.nexuspay.workflow.application.port.out;

import io.nexuspay.workflow.domain.*;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port for workflow builder persistence.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
public interface WorkflowBuilderRepository {

    WorkflowDefinition saveWorkflow(WorkflowDefinition workflow);

    Optional<WorkflowDefinition> findWorkflowById(String workflowId);

    /**
     * SEC-27: tenant-scoped by-id lookup. Returns the workflow only when it belongs to
     * {@code tenantId}; an absent OR foreign-tenant workflow yields an empty Optional (no
     * cross-tenant existence oracle). The tenant predicate is pushed to SQL so a foreign-tenant
     * row never leaves the database.
     */
    Optional<WorkflowDefinition> findWorkflowByIdAndTenantId(String workflowId, String tenantId);

    List<WorkflowDefinition> findWorkflowsByTenantId(String tenantId);

    WorkflowVersion saveVersion(WorkflowVersion version);

    Optional<WorkflowVersion> findVersionById(String versionId);

    /**
     * SEC-27: tenant-scoped by-id lookup for a version. {@code WorkflowVersion} carries no tenant of
     * its own — it is owned transitively through its parent workflow — so the finder joins to the
     * parent {@code workflow_definitions} row and filters on its {@code tenant_id}. Absent OR
     * foreign-tenant -> empty Optional (no existence oracle).
     */
    Optional<WorkflowVersion> findVersionByIdAndTenantId(String versionId, String tenantId);

    List<WorkflowVersion> findVersionsByWorkflowId(String workflowId);

    Optional<WorkflowVersion> findVersionByWorkflowIdAndNumber(String workflowId, int versionNumber);

    WebhookTrigger saveTrigger(WebhookTrigger trigger);

    Optional<WebhookTrigger> findTriggerById(String triggerId);

    /**
     * SEC-27: tenant-scoped by-id lookup for a webhook trigger. The trigger carries its own
     * {@code tenant_id}, so the predicate is pushed to SQL. Absent OR foreign-tenant -> empty
     * Optional (no existence oracle).
     */
    Optional<WebhookTrigger> findTriggerByIdAndTenantId(String triggerId, String tenantId);

    Optional<WebhookTrigger> findTriggerByUrlPath(String urlPath);

    WorkflowExecution saveExecution(WorkflowExecution execution);

    Optional<WorkflowExecution> findExecutionById(String executionId);

    /**
     * SEC-27: tenant-scoped by-id lookup for an execution. The execution carries its own
     * {@code tenant_id}, so the predicate is pushed to SQL. Absent OR foreign-tenant -> empty
     * Optional (no existence oracle).
     */
    Optional<WorkflowExecution> findExecutionByIdAndTenantId(String executionId, String tenantId);
}
