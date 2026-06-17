package io.nexuspay.workflow.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface JpaWorkflowVersionRepository extends JpaRepository<WorkflowVersionEntity, String> {
    List<WorkflowVersionEntity> findByWorkflowIdOrderByVersionNumberDesc(String workflowId);
    Optional<WorkflowVersionEntity> findByWorkflowIdAndVersionNumber(String workflowId, int versionNumber);

    /**
     * SEC-27: tenant-scoped by-id finder for a version. {@code WorkflowVersionEntity} has no
     * {@code tenant_id} column — a version is owned transitively through its parent workflow — so the
     * predicate is enforced by joining to the parent {@code WorkflowDefinitionEntity} (on
     * {@code v.workflowId = d.id}) and filtering on {@code d.tenantId}. A version whose parent belongs
     * to another tenant (or whose parent is absent) yields an empty result, so the tenant filter is
     * pushed to SQL and no foreign-tenant snapshot ever leaves the database.
     */
    @Query("select v from WorkflowVersionEntity v, WorkflowDefinitionEntity d "
            + "where v.id = :versionId and v.workflowId = d.id and d.tenantId = :tenantId")
    Optional<WorkflowVersionEntity> findByIdAndTenantId(@Param("versionId") String versionId,
                                                        @Param("tenantId") String tenantId);
}
