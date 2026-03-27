package io.nexuspay.analytics.application.service;

import io.nexuspay.analytics.application.dto.AnalyticsQuery;
import io.nexuspay.analytics.application.dto.AuthRateResponse;
import io.nexuspay.analytics.application.dto.AuthRateResponse.AuthRateDataPoint;
import io.nexuspay.analytics.application.port.in.QueryAuthRatesUseCase;
import io.nexuspay.analytics.application.port.out.AuthRateRollupRepository;
import io.nexuspay.analytics.domain.model.AnalyticsDimension;
import io.nexuspay.analytics.domain.model.AuthRateMetric;
import io.nexuspay.analytics.domain.model.TimeGranularity;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for querying authorization rate analytics across rollup granularities.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
@Service
public class AuthRateAnalyticsService implements QueryAuthRatesUseCase {

    private static final int MAX_DATE_RANGE_DAYS = 365;

    private final AuthRateRollupRepository repository;

    public AuthRateAnalyticsService(AuthRateRollupRepository repository) {
        this.repository = repository;
    }

    @Override
    public AuthRateResponse query(AnalyticsQuery query) {
        validateDateRange(query);

        TimeGranularity granularity = query.granularity() != null ? query.granularity() : TimeGranularity.DAILY;
        String pspFilter = query.filters() != null ? query.filters().get("psp") : null;
        String cardBrandFilter = query.filters() != null ? query.filters().get("cardBrand") : null;
        String currencyFilter = query.filters() != null ? query.filters().get("currency") : null;

        List<AuthRateMetric> metrics = switch (granularity) {
            case HOURLY -> repository.findHourly(query.tenantId(), query.from(), query.to(),
                    pspFilter, cardBrandFilter, currencyFilter);
            case DAILY -> repository.findDaily(query.tenantId(),
                    LocalDate.ofInstant(query.from(), ZoneOffset.UTC),
                    LocalDate.ofInstant(query.to(), ZoneOffset.UTC),
                    pspFilter, cardBrandFilter, currencyFilter);
            case MONTHLY -> repository.findMonthly(query.tenantId(),
                    LocalDate.ofInstant(query.from(), ZoneOffset.UTC),
                    LocalDate.ofInstant(query.to(), ZoneOffset.UTC),
                    pspFilter, cardBrandFilter, currencyFilter);
        };

        List<AuthRateDataPoint> dataPoints = metrics.stream()
                .map(m -> new AuthRateDataPoint(
                        m.bucketTime(),
                        buildDimensions(m, query.groupBy()),
                        m.totalAttempts(),
                        m.totalApproved(),
                        m.totalDeclined(),
                        m.totalErrors(),
                        m.authRate(),
                        m.avgLatencyMs(),
                        m.p95LatencyMs()
                ))
                .toList();

        return new AuthRateResponse(query.from(), query.to(), granularity, dataPoints);
    }

    private Map<String, String> buildDimensions(AuthRateMetric metric, List<AnalyticsDimension> groupBy) {
        if (groupBy == null || groupBy.isEmpty()) return Map.of();

        Map<String, String> dims = new HashMap<>();
        for (AnalyticsDimension dim : groupBy) {
            switch (dim) {
                case PSP -> { if (metric.pspConnector() != null) dims.put("psp", metric.pspConnector()); }
                case CARD_BRAND -> { if (metric.cardBrand() != null) dims.put("cardBrand", metric.cardBrand()); }
                case CARD_TYPE -> { if (metric.cardType() != null) dims.put("cardType", metric.cardType()); }
                case REGION -> { if (metric.issuingRegion() != null) dims.put("region", metric.issuingRegion()); }
                case CURRENCY -> { if (metric.currency() != null) dims.put("currency", metric.currency()); }
                case PAYMENT_METHOD -> { if (metric.paymentMethod() != null) dims.put("paymentMethod", metric.paymentMethod()); }
                default -> {}
            }
        }
        return dims;
    }

    private void validateDateRange(AnalyticsQuery query) {
        if (query.from() == null || query.to() == null) {
            throw new IllegalArgumentException("Date range (from, to) is required");
        }
        long days = Duration.between(query.from(), query.to()).toDays();
        if (days > MAX_DATE_RANGE_DAYS) {
            throw new IllegalArgumentException("Date range cannot exceed " + MAX_DATE_RANGE_DAYS + " days");
        }
        if (days < 0) {
            throw new IllegalArgumentException("'from' must be before 'to'");
        }
    }
}
