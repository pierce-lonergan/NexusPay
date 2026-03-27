package io.nexuspay.analytics.domain.event;

import java.time.Instant;

/**
 * Event published when a statistical anomaly is detected in analytics metrics.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
public record AnomalyDetected(
        String tenantId,
        String pspConnector,
        String metricName,
        double currentValue,
        double expectedValue,
        double stdDeviation,
        Instant detectedAt
) {}
