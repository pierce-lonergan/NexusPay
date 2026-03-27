package io.nexuspay.analytics.domain.model;

import java.time.Instant;
import java.util.Map;

/**
 * Alert generated when an anomaly is detected in analytics metrics.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
public record AnomalyAlert(
        String tenantId,
        String pspConnector,
        String alertType,
        Map<String, Object> details,
        Instant detectedAt
) {}
