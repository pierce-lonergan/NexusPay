package io.nexuspay.workflow.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JpaWorkflowVersionRepository extends JpaRepository<WorkflowVersionEntity, String> {
    List<WorkflowVersionEntity> findByWorkflowIdOrderByVersionNumberDesc(String workflowId);
    Optional<WorkflowVersionEntity> findByWorkflowIdAndVersionNumber(String workflowId, int versionNumber);
}
