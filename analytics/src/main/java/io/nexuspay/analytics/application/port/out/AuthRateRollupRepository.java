package io.nexuspay.analytics.application.port.out;

import io.nexuspay.analytics.domain.model.AuthRateMetric;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Out-port for authorization rate rollup persistence.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
public interface AuthRateRollupRepository {

    void saveHourly(AuthRateMetric metric);

    void saveDaily(AuthRateMetric metric);

    void saveMonthly(AuthRateMetric metric);

    List<AuthRateMetric> findHourly(String tenantId, Instant from, Instant to,
                                     String pspConnector, String cardBrand, String currency);

    List<AuthRateMetric> findDaily(String tenantId, LocalDate from, LocalDate to,
                                    String pspConnector, String cardBrand, String currency);

    List<AuthRateMetric> findMonthly(String tenantId, LocalDate from, LocalDate to,
                                      String pspConnector, String cardBrand, String currency);

    void upsertHourly(AuthRateMetric metric);
}
