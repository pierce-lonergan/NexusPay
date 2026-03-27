package io.nexuspay.analytics.application.dto;

import io.nexuspay.analytics.domain.model.TimeGranularity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response for revenue analytics queries.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
public record RevenueResponse(
        Instant from,
        Instant to,
        TimeGranularity granularity,
        List<RevenueDataPoint> data
) {
    public record RevenueDataPoint(
            Instant timestamp,
            Map<String, String> dimensions,
            BigDecimal totalVolume,
            int totalCount,
            BigDecimal totalFees,
            BigDecimal netRevenue,
            BigDecimal refundVolume,
            int refundCount,
            BigDecimal chargebackVolume,
            int chargebackCount
    ) {}
}
