package io.nexuspay.analytics.application.dto;

import io.nexuspay.analytics.domain.model.AnalyticsDimension;
import io.nexuspay.analytics.domain.model.TimeGranularity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Query parameters for analytics API endpoints.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
public record AnalyticsQuery(
        Instant from,
        Instant to,
        TimeGranularity granularity,
        List<AnalyticsDimension> groupBy,
        Map<String, String> filters,
        String tenantId
) {}
