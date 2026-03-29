package io.nexuspay.workflow.application.port.in;

import java.time.Instant;
import java.util.List;

/**
 * Use case for viewing workflow version history and rollback.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
public interface ManageWorkflowVersionUseCase {

    List<VersionInfo> listVersions(String workflowId, String tenantId);

    VersionInfo getVersion(String versionId, String tenantId);

    ManageWorkflowUseCase.WorkflowResult rollbackToVersion(String workflowId, String tenantId,
                                                             int targetVersion, String publishedBy);

    record VersionInfo(
            String versionId,
            String workflowId,
            int versionNumber,
            String graphSnapshot,
            String changeDescription,
            String publishedBy,
            Instant createdAt
    ) {}
}
