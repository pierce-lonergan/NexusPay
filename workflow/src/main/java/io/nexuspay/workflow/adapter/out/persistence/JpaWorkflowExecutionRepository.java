package io.nexuspay.workflow.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface JpaWorkflowExecutionRepository extends JpaRepository<WorkflowExecutionEntity, String> {

    // SEC-27: tenant-scoped by-id finder — the tenant predicate is pushed to SQL.
    Optional<WorkflowExecutionEntity> findByIdAndTenantId(String id, String tenantId);
}
