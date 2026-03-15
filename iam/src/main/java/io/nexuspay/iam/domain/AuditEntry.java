package io.nexuspay.iam.domain;

import java.time.Instant;
import java.util.Map;

/**
 * Audit log entry for tracking all significant actions.
 */
public record AuditEntry(
        String id,
        String actor,
        String action,
        String resourceType,
        String resourceId,
        Map<String, Object> details,
        String ipAddress,
        String tenantId,
        Instant timestamp
) {}
