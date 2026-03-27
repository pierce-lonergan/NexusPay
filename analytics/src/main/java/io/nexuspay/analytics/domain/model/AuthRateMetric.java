package io.nexuspay.analytics.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Authorization rate metric for a specific time bucket and dimension combination.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
public record AuthRateMetric(
        String tenantId,
        Instant bucketTime,
        String pspConnector,
        String cardBrand,
        String cardType,
        String issuingRegion,
        String currency,
        String paymentMethod,
        int totalAttempts,
        int totalApproved,
        int totalDeclined,
        int totalErrors,
        BigDecimal authRate,
        Integer avgLatencyMs,
        Integer p50LatencyMs,
        Integer p95LatencyMs,
        Integer p99LatencyMs
) {}
