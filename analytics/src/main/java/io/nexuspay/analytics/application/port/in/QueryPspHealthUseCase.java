package io.nexuspay.analytics.application.port.in;

import io.nexuspay.analytics.application.dto.AnalyticsQuery;
import io.nexuspay.analytics.application.dto.PspHealthResponse;
import io.nexuspay.analytics.domain.model.PspHealthScore;

/**
 * Use case for querying PSP health scores and anomaly status.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
public interface QueryPspHealthUseCase {

    PspHealthResponse query(AnalyticsQuery query);

    PspHealthScore calculateHealthScore(String pspConnector, String tenantId);
}
