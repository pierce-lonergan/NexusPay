package io.nexuspay.analytics.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response for PSP health analytics queries.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
public record PspHealthResponse(
        List<PspHealthDataPoint> data
) {
    public record PspHealthDataPoint(
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
}
