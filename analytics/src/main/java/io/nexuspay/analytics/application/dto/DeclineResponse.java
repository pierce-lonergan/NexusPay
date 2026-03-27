package io.nexuspay.analytics.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Response for decline analytics queries.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
public record DeclineResponse(
        LocalDate from,
        LocalDate to,
        List<DeclineDataPoint> data
) {
    public record DeclineDataPoint(
            LocalDate date,
            Map<String, String> dimensions,
            String declineCode,
            String declineCategory,
            int totalCount,
            BigDecimal totalVolume
    ) {}
}
