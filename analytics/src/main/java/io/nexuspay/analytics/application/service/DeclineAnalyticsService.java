package io.nexuspay.analytics.application.service;

import io.nexuspay.analytics.application.dto.AnalyticsQuery;
import io.nexuspay.analytics.application.dto.DeclineResponse;
import io.nexuspay.analytics.application.dto.DeclineResponse.DeclineDataPoint;
import io.nexuspay.analytics.application.port.in.QueryDeclinesUseCase;
import io.nexuspay.analytics.application.port.out.DeclineRollupRepository;
import io.nexuspay.analytics.domain.model.AnalyticsDimension;
import io.nexuspay.analytics.domain.model.DeclineAnalysis;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service for querying decline analytics with soft/hard categorization.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
@Service
public class DeclineAnalyticsService implements QueryDeclinesUseCase {

    private static final int MAX_DATE_RANGE_DAYS = 365;

    /** Soft decline codes — retriable. */
    private static final Set<String> SOFT_DECLINE_CODES = Set.of(
            "insufficient_funds", "card_velocity_exceeded", "do_not_honor",
            "try_again", "issuer_not_available", "processing_error",
            "reenter_transaction", "service_not_allowed"
    );

    /** Hard decline codes — permanent. */
    private static final Set<String> HARD_DECLINE_CODES = Set.of(
            "card_declined", "expired_card", "incorrect_number", "invalid_account",
            "stolen_card", "lost_card", "pickup_card", "restricted_card",
            "security_violation", "fraudulent", "invalid_cvc"
    );

    private final DeclineRollupRepository repository;

    public DeclineAnalyticsService(DeclineRollupRepository repository) {
        this.repository = repository;
    }

    @Override
    public DeclineResponse query(AnalyticsQuery query) {
        validateDateRange(query);

        String pspFilter = query.filters() != null ? query.filters().get("psp") : null;
        String declineCodeFilter = query.filters() != null ? query.filters().get("declineCode") : null;
        String cardBrandFilter = query.filters() != null ? query.filters().get("cardBrand") : null;

        LocalDate from = LocalDate.ofInstant(query.from(), ZoneOffset.UTC);
        LocalDate to = LocalDate.ofInstant(query.to(), ZoneOffset.UTC);

        List<DeclineAnalysis> declines = repository.findDaily(
                query.tenantId(), from, to, pspFilter, declineCodeFilter, cardBrandFilter);

        List<DeclineDataPoint> dataPoints = declines.stream()
                .map(d -> new DeclineDataPoint(
                        d.bucketDate(),
                        buildDimensions(d, query.groupBy()),
                        d.declineCode(),
                        d.declineCategory(),
                        d.totalCount(),
                        d.totalVolume()
                ))
                .toList();

        return new DeclineResponse(from, to, dataPoints);
    }

    /**
     * Categorizes a decline code as SOFT, HARD, or ERROR.
     */
    public static String categorizeDecline(String declineCode) {
        if (declineCode == null) return "ERROR";
        String code = declineCode.toLowerCase();
        if (SOFT_DECLINE_CODES.contains(code)) return "SOFT";
        if (HARD_DECLINE_CODES.contains(code)) return "HARD";
        return "ERROR";
    }

    private Map<String, String> buildDimensions(DeclineAnalysis decline, List<AnalyticsDimension> groupBy) {
        if (groupBy == null || groupBy.isEmpty()) return Map.of();

        Map<String, String> dims = new HashMap<>();
        for (AnalyticsDimension dim : groupBy) {
            switch (dim) {
                case PSP -> { if (decline.pspConnector() != null) dims.put("psp", decline.pspConnector()); }
                case CARD_BRAND -> { if (decline.cardBrand() != null) dims.put("cardBrand", decline.cardBrand()); }
                case REGION -> { if (decline.issuingRegion() != null) dims.put("region", decline.issuingRegion()); }
                case DECLINE_REASON -> { if (decline.declineCode() != null) dims.put("declineCode", decline.declineCode()); }
                default -> {}
            }
        }
        return dims;
    }

    private void validateDateRange(AnalyticsQuery query) {
        if (query.from() == null || query.to() == null) {
            throw new IllegalArgumentException("Date range (from, to) is required");
        }
        if (Duration.between(query.from(), query.to()).toDays() > MAX_DATE_RANGE_DAYS) {
            throw new IllegalArgumentException("Date range cannot exceed " + MAX_DATE_RANGE_DAYS + " days");
        }
    }
}
