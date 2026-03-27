package io.nexuspay.analytics.application.port.out;

import io.nexuspay.analytics.domain.model.DeclineAnalysis;

import java.time.LocalDate;
import java.util.List;

/**
 * Out-port for decline rollup persistence.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
public interface DeclineRollupRepository {

    void saveDaily(DeclineAnalysis decline);

    List<DeclineAnalysis> findDaily(String tenantId, LocalDate from, LocalDate to,
                                     String pspConnector, String declineCode, String cardBrand);

    void upsertDaily(DeclineAnalysis decline);
}
