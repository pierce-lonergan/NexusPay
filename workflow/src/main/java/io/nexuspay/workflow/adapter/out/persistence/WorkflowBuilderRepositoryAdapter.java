package io.nexuspay.workflow.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.workflow.application.port.out.WorkflowBuilderRepository;
import io.nexuspay.workflow.domain.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Adapter implementing {@link WorkflowBuilderRepository} via JPA.
 * Handles JSONB serialization of nodes and edges.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
@Component
public class WorkflowBuilderRepositoryAdapter implements WorkflowBuilderRepository {

    private final JpaWorkflowDefinitionRepository definitionRepo;
    private final JpaWorkflowVersionRepository versionRepo;
    private final JpaWebhookTriggerRepository triggerRepo;
    private final JpaWorkflowExecutionRepository executionRepo;
    private final ObjectMapper objectMapper;

    public WorkflowBuilderRepositoryAdapter(JpaWorkflowDefinitionRepository definitionRepo,
                                             JpaWorkflowVersionRepository versionRepo,
                                             JpaWebhookTriggerRepository triggerRepo,
                                             JpaWorkflowExecutionRepository executionRepo,
                                             ObjectMapper objectMapper) {
        this.definitionRepo = definitionRepo;
        this.versionRepo = versionRepo;
        this.triggerRepo = triggerRepo;
        this.executionRepo = executionRepo;
        this.objectMapper = objectMapper;
    }

    // --- WorkflowDefinition ---

    @Override
    public WorkflowDefinition saveWorkflow(WorkflowDefinition workflow) {
        definitionRepo.save(toDefinitionEntity(workflow));
        return workflow;
    }

    @Override
    public Optional<WorkflowDefinition> findWorkflowById(String workflowId) {
        return definitionRepo.findById(workflowId).map(this::toDefinitionDomain);
    }

    @Override
    public List<WorkflowDefinition> findWorkflowsByTenantId(String tenantId) {
        return definitionRepo.findByTenantId(tenantId).stream()
                .map(this::toDefinitionDomain).toList();
    }

    // --- WorkflowVersion ---

    @Override
    public WorkflowVersion saveVersion(WorkflowVersion version) {
        versionRepo.save(toVersionEntity(version));
        return version;
    }

    @Override
    public Optional<WorkflowVersion> findVersionById(String versionId) {
        return versionRepo.findById(versionId).map(this::toVersionDomain);
    }

    @Override
    public List<WorkflowVersion> findVersionsByWorkflowId(String workflowId) {
        return versionRepo.findByWorkflowIdOrderByVersionNumberDesc(workflowId).stream()
                .map(this::toVersionDomain).toList();
    }

    @Override
    public Optional<WorkflowVersion> findVersionByWorkflowIdAndNumber(String workflowId, int versionNumber) {
        return versionRepo.findByWorkflowIdAndVersionNumber(workflowId, versionNumber)
                .map(this::toVersionDomain);
    }

    // --- WebhookTrigger ---

    @Override
    public WebhookTrigger saveTrigger(WebhookTrigger trigger) {
        triggerRepo.save(toTriggerEntity(trigger));
        return trigger;
    }

    @Override
    public Optional<WebhookTrigger> findTriggerById(String triggerId) {
        return triggerRepo.findById(triggerId).map(this::toTriggerDomain);
    }

    @Override
    public Optional<WebhookTrigger> findTriggerByUrlPath(String urlPath) {
        return triggerRepo.findByUrlPath(urlPath).map(this::toTriggerDomain);
    }

    // --- WorkflowExecution ---

    @Override
    public WorkflowExecution saveExecution(WorkflowExecution execution) {
        executionRepo.save(toExecutionEntity(execution));
        return execution;
    }

    @Override
    public Optional<WorkflowExecution> findExecutionById(String executionId) {
        return executionRepo.findById(executionId).map(this::toExecutionDomain);
    }

    // --- Mapping: WorkflowDefinition ---

    private WorkflowDefinitionEntity toDefinitionEntity(WorkflowDefinition d) {
        WorkflowDefinitionEntity e = new WorkflowDefinitionEntity();
        e.setId(d.getId());
        e.setTenantId(d.getTenantId());
        e.setName(d.getName());
        e.setDescription(d.getDescription());
        e.setStatus(d.getStatus().name());
        e.setVersion(d.getVersion());
        e.setTriggerType(d.getTriggerType().name());
        e.setTriggerConfig(d.getTriggerConfig());
        e.setNodes(serializeJson(d.getNodes()));
        e.setEdges(serializeJson(d.getEdges()));
        e.setCreatedAt(d.getCreatedAt());
        e.setUpdatedAt(d.getUpdatedAt());
        e.setCreatedBy(d.getCreatedBy());
        return e;
    }

    private WorkflowDefinition toDefinitionDomain(WorkflowDefinitionEntity e) {
        WorkflowDefinition d = new WorkflowDefinition();
        d.setId(e.getId());
        d.setTenantId(e.getTenantId());
        d.setName(e.getName());
        d.setDescription(e.getDescription());
        d.setStatus(WorkflowStatus.valueOf(e.getStatus()));
        d.setVersion(e.getVersion());
        d.setTriggerType(TriggerType.valueOf(e.getTriggerType()));
        d.setTriggerConfig(e.getTriggerConfig());
        d.setNodes(deserializeNodes(e.getNodes()));
        d.setEdges(deserializeEdges(e.getEdges()));
        d.setCreatedAt(e.getCreatedAt());
        d.setUpdatedAt(e.getUpdatedAt());
        d.setCreatedBy(e.getCreatedBy());
        return d;
    }

    // --- Mapping: WorkflowVersion ---

    private WorkflowVersionEntity toVersionEntity(WorkflowVersion v) {
        WorkflowVersionEntity e = new WorkflowVersionEntity();
        e.setId(v.getId());
        e.setWorkflowId(v.getWorkflowId());
        e.setVersionNumber(v.getVersionNumber());
        e.setGraphSnapshot(v.getGraphSnapshot());
        e.setChangeDescription(v.getChangeDescription());
        e.setPublishedBy(v.getPublishedBy());
        e.setCreatedAt(v.getCreatedAt());
        return e;
    }

    private WorkflowVersion toVersionDomain(WorkflowVersionEntity e) {
        WorkflowVersion v = new WorkflowVersion();
        v.setId(e.getId());
        v.setWorkflowId(e.getWorkflowId());
        v.setVersionNumber(e.getVersionNumber());
        v.setGraphSnapshot(e.getGraphSnapshot());
        v.setChangeDescription(e.getChangeDescription());
        v.setPublishedBy(e.getPublishedBy());
        v.setCreatedAt(e.getCreatedAt());
        return v;
    }

    // --- Mapping: WebhookTrigger ---

    private WebhookTriggerEntity toTriggerEntity(WebhookTrigger t) {
        WebhookTriggerEntity e = new WebhookTriggerEntity();
        e.setId(t.getId());
        e.setTenantId(t.getTenantId());
        e.setWorkflowId(t.getWorkflowId());
        e.setUrlPath(t.getUrlPath());
        e.setSecret(t.getSecret());
        e.setActive(t.isActive());
        e.setCreatedAt(t.getCreatedAt());
        return e;
    }

    private WebhookTrigger toTriggerDomain(WebhookTriggerEntity e) {
        WebhookTrigger t = new WebhookTrigger();
        t.setId(e.getId());
        t.setTenantId(e.getTenantId());
        t.setWorkflowId(e.getWorkflowId());
        t.setUrlPath(e.getUrlPath());
        t.setSecret(e.getSecret());
        t.setActive(e.isActive());
        t.setCreatedAt(e.getCreatedAt());
        return t;
    }

    // --- Mapping: WorkflowExecution ---

    private WorkflowExecutionEntity toExecutionEntity(WorkflowExecution ex) {
        WorkflowExecutionEntity e = new WorkflowExecutionEntity();
        e.setId(ex.getId());
        e.setTenantId(ex.getTenantId());
        e.setWorkflowId(ex.getWorkflowId());
        e.setWorkflowVersion(ex.getWorkflowVersion());
        e.setTemporalWorkflowId(ex.getTemporalWorkflowId());
        e.setStatus(ex.getStatus().name());
        e.setTriggerPayload(ex.getTriggerPayload());
        e.setResultPayload(ex.getResultPayload());
        e.setFailureReason(ex.getFailureReason());
        e.setCurrentNodeId(ex.getCurrentNodeId());
        e.setStartedAt(ex.getStartedAt());
        e.setCompletedAt(ex.getCompletedAt());
        return e;
    }

    private WorkflowExecution toExecutionDomain(WorkflowExecutionEntity e) {
        WorkflowExecution ex = new WorkflowExecution();
        ex.setId(e.getId());
        ex.setTenantId(e.getTenantId());
        ex.setWorkflowId(e.getWorkflowId());
        ex.setWorkflowVersion(e.getWorkflowVersion());
        ex.setTemporalWorkflowId(e.getTemporalWorkflowId());
        ex.setStatus(ExecutionStatus.valueOf(e.getStatus()));
        ex.setTriggerPayload(e.getTriggerPayload());
        ex.setResultPayload(e.getResultPayload());
        ex.setFailureReason(e.getFailureReason());
        ex.setCurrentNodeId(e.getCurrentNodeId());
        ex.setStartedAt(e.getStartedAt());
        ex.setCompletedAt(e.getCompletedAt());
        return ex;
    }

    // --- JSON helpers ---

    private String serializeJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize to JSON", e);
        }
    }

    private List<WorkflowNode> deserializeNodes(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize workflow nodes", e);
        }
    }

    private List<WorkflowEdge> deserializeEdges(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize workflow edges", e);
        }
    }
}
