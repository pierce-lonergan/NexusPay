package io.nexuspay.workflow.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JpaWorkflowDefinitionRepository extends JpaRepository<WorkflowDefinitionEntity, String> {
    List<WorkflowDefinitionEntity> findByTenantId(String tenantId);

    // SEC-27: tenant-scoped by-id finder — the tenant predicate is pushed to SQL.
    Optional<WorkflowDefinitionEntity> findByIdAndTenantId(String id, String tenantId);
}
