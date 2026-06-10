package io.nexuspay.iam.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface JpaPendingApprovalRepository extends JpaRepository<PendingApprovalEntity, String> {

    List<PendingApprovalEntity> findAllByStatusAndTenantId(String status, String tenantId);

    /**
     * Atomically claims a PENDING approval, moving it to {@code newStatus} only if
     * it is still PENDING and belongs to the tenant. Returns rows affected (1 = this
     * caller won the transition, 0 = already processed / wrong tenant). This makes
     * approve/reject execute-once even under concurrent requests (maker-checker for
     * money — B-009), without a read-then-write TOCTOU.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PendingApprovalEntity a SET a.status = :newStatus, a.reviewedBy = :reviewer, "
            + "a.reviewedAt = :now WHERE a.id = :id AND a.tenantId = :tenantId AND a.status = 'PENDING'")
    int transitionFromPending(@Param("id") String id,
                              @Param("tenantId") String tenantId,
                              @Param("newStatus") String newStatus,
                              @Param("reviewer") String reviewer,
                              @Param("now") Instant now);
}
