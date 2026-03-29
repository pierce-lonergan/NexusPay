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

    List<WorkflowDefinition> findWorkflowsByTenantId(String tenantId);

    WorkflowVersion saveVersion(WorkflowVersion version);

    Optional<WorkflowVersion> findVersionById(String versionId);

    List<WorkflowVersion> findVersionsByWorkflowId(String workflowId);

    Optional<WorkflowVersion> findVersionByWorkflowIdAndNumber(String workflowId, int versionNumber);

    WebhookTrigger saveTrigger(WebhookTrigger trigger);

    Optional<WebhookTrigger> findTriggerById(String triggerId);

    Optional<WebhookTrigger> findTriggerByUrlPath(String urlPath);

    WorkflowExecution saveExecution(WorkflowExecution execution);

    Optional<WorkflowExecution> findExecutionById(String executionId);
}
