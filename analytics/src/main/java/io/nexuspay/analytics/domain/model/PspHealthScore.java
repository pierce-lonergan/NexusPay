package io.nexuspay.analytics.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Composite PSP health score with component breakdown.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
public record PspHealthScore(
        String tenantId,
        String pspConnector,
        Instant snapshotTime,
        int healthScore,
        int authRateScore,
        int latencyScore,
        int errorRateScore,
        BigDecimal authRate7d,
        Integer avgLatencyMs,
        Integer p95LatencyMs,
        BigDecimal errorRate,
        boolean anomalyDetected,
        Map<String, Object> anomalyDetails
) {}
