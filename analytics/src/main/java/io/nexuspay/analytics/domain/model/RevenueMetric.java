package io.nexuspay.analytics.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Revenue metric for a specific time bucket and dimension combination.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
public record RevenueMetric(
        String tenantId,
        Instant bucketTime,
        String pspConnector,
        String currency,
        String paymentMethod,
        BigDecimal totalVolume,
        int totalCount,
        BigDecimal totalFees,
        BigDecimal netRevenue,
        BigDecimal refundVolume,
        int refundCount,
        BigDecimal chargebackVolume,
        int chargebackCount
) {}
