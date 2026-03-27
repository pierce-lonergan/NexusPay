package io.nexuspay.analytics.application.port.out;

import io.nexuspay.analytics.domain.model.RevenueMetric;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Out-port for revenue rollup persistence.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
public interface RevenueRollupRepository {

    void saveHourly(RevenueMetric metric);

    void saveDaily(RevenueMetric metric);

    List<RevenueMetric> findHourly(String tenantId, Instant from, Instant to,
                                    String pspConnector, String currency);

    List<RevenueMetric> findDaily(String tenantId, LocalDate from, LocalDate to,
                                   String pspConnector, String currency);

    void upsertHourly(RevenueMetric metric);
}
