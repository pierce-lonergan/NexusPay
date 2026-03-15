package io.nexuspay.iam.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JpaPendingApprovalRepository extends JpaRepository<PendingApprovalEntity, String> {

    List<PendingApprovalEntity> findAllByStatusAndTenantId(String status, String tenantId);
}
