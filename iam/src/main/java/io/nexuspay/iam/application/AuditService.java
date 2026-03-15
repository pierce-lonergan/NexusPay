package io.nexuspay.iam.application;

import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.iam.adapter.out.persistence.AuditLogEntity;
import io.nexuspay.iam.adapter.out.persistence.JpaAuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Synchronous audit logging service.
 * Financial operations call this explicitly. Non-financial operations use @Audited AOP.
 *
 * Phase 1: synchronous DB write only (no Kafka topic).
 * Phase 2: add async Kafka publish for audit events.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final JpaAuditLogRepository auditLogRepository;

    public AuditService(JpaAuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAction(String actor, String action, String resourceType,
                          String resourceId, Map<String, Object> details,
                          String ipAddress, String tenantId) {
        var entity = new AuditLogEntity(
                PrefixedId.audit(),
                actor,
                action,
                resourceType,
                resourceId,
                details,
                ipAddress,
                tenantId,
                Instant.now()
        );
        auditLogRepository.save(entity);
        log.debug("Audit: actor={} action={} resource={}:{}", actor, action, resourceType, resourceId);
    }
}
