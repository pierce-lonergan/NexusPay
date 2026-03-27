package io.nexuspay.analytics.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Decline analysis metric for a specific date and dimension combination.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
public record DeclineAnalysis(
        String tenantId,
        LocalDate bucketDate,
        String pspConnector,
        String declineCode,
        String declineCategory,
        String cardBrand,
        String issuingRegion,
        String issuerName,
        int totalCount,
        BigDecimal totalVolume
) {}
