package io.nexuspay.iam.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaAuditLogRepository extends JpaRepository<AuditLogEntity, String> {
}
