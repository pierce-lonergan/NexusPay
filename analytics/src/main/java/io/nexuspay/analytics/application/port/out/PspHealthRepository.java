package io.nexuspay.analytics.application.port.out;

import io.nexuspay.analytics.domain.model.PspHealthScore;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Out-port for PSP health snapshot persistence.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
public interface PspHealthRepository {

    void save(PspHealthScore score);

    Optional<PspHealthScore> findLatest(String tenantId, String pspConnector);

    List<PspHealthScore> findAllLatest(String tenantId);

    List<PspHealthScore> findTrend(String tenantId, String pspConnector, Instant from, Instant to);
}
