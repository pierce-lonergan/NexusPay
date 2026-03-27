package io.nexuspay.analytics.application.port.in;

import io.nexuspay.analytics.application.dto.AnalyticsQuery;
import io.nexuspay.analytics.application.dto.DeclineResponse;

/**
 * Use case for querying decline analytics.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
public interface QueryDeclinesUseCase {

    DeclineResponse query(AnalyticsQuery query);
}
