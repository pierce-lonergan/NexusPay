package io.nexuspay.analytics.application.port.in;

import io.nexuspay.analytics.application.dto.AnalyticsQuery;
import io.nexuspay.analytics.application.dto.AuthRateResponse;

/**
 * Use case for querying authorization rate analytics.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
public interface QueryAuthRatesUseCase {

    AuthRateResponse query(AnalyticsQuery query);
}
