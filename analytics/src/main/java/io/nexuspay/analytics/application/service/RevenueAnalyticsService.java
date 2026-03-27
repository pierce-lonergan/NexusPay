package io.nexuspay.analytics.application.service;

import io.nexuspay.analytics.application.dto.AnalyticsQuery;
import io.nexuspay.analytics.application.dto.RevenueResponse;
import io.nexuspay.analytics.application.dto.RevenueResponse.RevenueDataPoint;
import io.nexuspay.analytics.application.port.in.QueryRevenueUseCase;
import io.nexuspay.analytics.application.port.out.RevenueRollupRepository;
import io.nexuspay.analytics.domain.model.AnalyticsDimension;
import io.nexuspay.analytics.domain.model.RevenueMetric;
import io.nexuspay.analytics.domain.model.TimeGranularity;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for querying revenue analytics.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
@Service
public class RevenueAnalyticsService implements QueryRevenueUseCase {

    private static final int MAX_DATE_RANGE_DAYS = 365;

    private final RevenueRollupRepository repository;

    public RevenueAnalyticsService(RevenueRollupRepository repository) {
        this.repository = repository;
    }

    @Override
    public RevenueResponse query(AnalyticsQuery query) {
        validateDateRange(query);

        TimeGranularity granularity = query.granularity() != null ? query.granularity() : TimeGranularity.DAILY;
        String pspFilter = query.filters() != null ? query.filters().get("psp") : null;
        String currencyFilter = query.filters() != null ? query.filters().get("currency") : null;

        List<RevenueMetric> metrics = switch (granularity) {
            case HOURLY -> repository.findHourly(query.tenantId(), query.from(), query.to(),
                    pspFilter, currencyFilter);
            case DAILY, MONTHLY -> repository.findDaily(query.tenantId(),
                    LocalDate.ofInstant(query.from(), ZoneOffset.UTC),
                    LocalDate.ofInstant(query.to(), ZoneOffset.UTC),
                    pspFilter, currencyFilter);
        };

        List<RevenueDataPoint> dataPoints = metrics.stream()
                .map(m -> new RevenueDataPoint(
                        m.bucketTime(),
                        buildDimensions(m, query.groupBy()),
                        m.totalVolume(),
                        m.totalCount(),
                        m.totalFees(),
                        m.netRevenue(),
                        m.refundVolume(),
                        m.refundCount(),
                        m.chargebackVolume(),
                        m.chargebackCount()
                ))
                .toList();

        return new RevenueResponse(query.from(), query.to(), granularity, dataPoints);
    }

    private Map<String, String> buildDimensions(RevenueMetric metric, List<AnalyticsDimension> groupBy) {
        if (groupBy == null || groupBy.isEmpty()) return Map.of();

        Map<String, String> dims = new HashMap<>();
        for (AnalyticsDimension dim : groupBy) {
            switch (dim) {
                case PSP -> { if (metric.pspConnector() != null) dims.put("psp", metric.pspConnector()); }
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
        if (Duration.between(query.from(), query.to()).toDays() > MAX_DATE_RANGE_DAYS) {
            throw new IllegalArgumentException("Date range cannot exceed " + MAX_DATE_RANGE_DAYS + " days");
        }
    }
}
