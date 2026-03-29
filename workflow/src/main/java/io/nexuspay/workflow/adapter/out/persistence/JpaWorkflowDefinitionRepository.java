package io.nexuspay.workflow.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JpaWorkflowDefinitionRepository extends JpaRepository<WorkflowDefinitionEntity, String> {
    List<WorkflowDefinitionEntity> findByTenantId(String tenantId);
}
