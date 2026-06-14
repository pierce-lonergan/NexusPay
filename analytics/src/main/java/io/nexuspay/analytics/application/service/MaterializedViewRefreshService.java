package io.nexuspay.analytics.application.service;

import io.nexuspay.common.rls.SystemTransactional;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled job for refreshing analytics materialized views.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
@Service
public class MaterializedViewRefreshService {

    private static final Logger LOG = LoggerFactory.getLogger(MaterializedViewRefreshService.class);

    private final EntityManager entityManager;

    public MaterializedViewRefreshService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Refreshes materialized views every hour.
     * Uses CONCURRENTLY to avoid locking reads during refresh.
     */
    @SystemTransactional
    @Scheduled(cron = "${nexuspay.analytics.rollup.materialized-view-refresh-cron:0 0 * * * *}")
    @Transactional
    public void refreshMaterializedViews() {
        LOG.info("Starting materialized view refresh");

        try {
            entityManager.createNativeQuery(
                    "REFRESH MATERIALIZED VIEW CONCURRENTLY analytics.mv_auth_rate_daily_refresh"
            ).executeUpdate();
            LOG.debug("Refreshed mv_auth_rate_daily_refresh");
        } catch (Exception e) {
            LOG.warn("Failed to refresh mv_auth_rate_daily_refresh: {}", e.getMessage());
        }

        try {
            entityManager.createNativeQuery(
                    "REFRESH MATERIALIZED VIEW CONCURRENTLY analytics.mv_psp_health_trend"
            ).executeUpdate();
            LOG.debug("Refreshed mv_psp_health_trend");
        } catch (Exception e) {
            LOG.warn("Failed to refresh mv_psp_health_trend: {}", e.getMessage());
        }

        LOG.info("Materialized view refresh complete");
    }
}
