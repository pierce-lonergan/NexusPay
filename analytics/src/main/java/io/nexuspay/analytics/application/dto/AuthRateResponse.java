package io.nexuspay.analytics.application.dto;

import io.nexuspay.analytics.domain.model.TimeGranularity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response for authorization rate analytics queries.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
public record AuthRateResponse(
        Instant from,
        Instant to,
        TimeGranularity granularity,
        List<AuthRateDataPoint> data
) {
    public record AuthRateDataPoint(
            Instant timestamp,
            Map<String, String> dimensions,
            int totalAttempts,
            int totalApproved,
            int totalDeclined,
            int totalErrors,
            BigDecimal authRate,
            Integer avgLatencyMs,
            Integer p95LatencyMs
    ) {}
}
