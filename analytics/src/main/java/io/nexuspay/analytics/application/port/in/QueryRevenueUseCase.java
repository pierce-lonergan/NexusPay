package io.nexuspay.analytics.application.port.in;

import io.nexuspay.analytics.application.dto.AnalyticsQuery;
import io.nexuspay.analytics.application.dto.RevenueResponse;

/**
 * Use case for querying revenue analytics.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
public interface QueryRevenueUseCase {

    RevenueResponse query(AnalyticsQuery query);
}
