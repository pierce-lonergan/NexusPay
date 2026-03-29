package io.nexuspay.workflow.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaWorkflowExecutionRepository extends JpaRepository<WorkflowExecutionEntity, String> {
}
