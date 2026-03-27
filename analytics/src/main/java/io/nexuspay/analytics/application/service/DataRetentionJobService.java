package io.nexuspay.analytics.application.service;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled job for cleaning up old analytics data based on configurable retention periods.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
@Service
public class DataRetentionJobService {

    private static final Logger LOG = LoggerFactory.getLogger(DataRetentionJobService.class);

    @Value("${nexuspay.analytics.rollup.hourly-retention-days:90}")
    private int hourlyRetentionDays;

    @Value("${nexuspay.analytics.rollup.daily-retention-days:730}")
    private int dailyRetentionDays;

    @Value("${nexuspay.analytics.rollup.monthly-retention-days:3650}")
    private int monthlyRetentionDays;

    private final EntityManager entityManager;

    public DataRetentionJobService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Runs daily at 04:00 UTC to clean up expired analytics data.
     */
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void cleanupExpiredData() {
        LOG.info("Starting analytics data retention cleanup");

        // Hourly data: 90 days
        int hourlyAuth = entityManager.createNativeQuery(
                "DELETE FROM analytics.auth_rate_hourly WHERE bucket_hour < NOW() - CAST(:days || ' days' AS INTERVAL)")
                .setParameter("days", hourlyRetentionDays)
                .executeUpdate();

        int hourlyRevenue = entityManager.createNativeQuery(
                "DELETE FROM analytics.revenue_hourly WHERE bucket_hour < NOW() - CAST(:days || ' days' AS INTERVAL)")
                .setParameter("days", hourlyRetentionDays)
                .executeUpdate();

        // Daily data: 730 days (2 years)
        int dailyAuth = entityManager.createNativeQuery(
                "DELETE FROM analytics.auth_rate_daily WHERE bucket_date < CURRENT_DATE - CAST(:days || ' days' AS INTERVAL)")
                .setParameter("days", dailyRetentionDays)
                .executeUpdate();

        int dailyRevenue = entityManager.createNativeQuery(
                "DELETE FROM analytics.revenue_daily WHERE bucket_date < CURRENT_DATE - CAST(:days || ' days' AS INTERVAL)")
                .setParameter("days", dailyRetentionDays)
                .executeUpdate();

        int dailyDecline = entityManager.createNativeQuery(
                "DELETE FROM analytics.decline_daily WHERE bucket_date < CURRENT_DATE - CAST(:days || ' days' AS INTERVAL)")
                .setParameter("days", dailyRetentionDays)
                .executeUpdate();

        // Monthly data: 3650 days (10 years)
        int monthlyAuth = entityManager.createNativeQuery(
                "DELETE FROM analytics.auth_rate_monthly WHERE bucket_month < CURRENT_DATE - CAST(:days || ' days' AS INTERVAL)")
                .setParameter("days", monthlyRetentionDays)
                .executeUpdate();

        // PSP health snapshots: 90 days
        int healthSnapshots = entityManager.createNativeQuery(
                "DELETE FROM analytics.psp_health_snapshots WHERE snapshot_time < NOW() - CAST(:days || ' days' AS INTERVAL)")
                .setParameter("days", hourlyRetentionDays)
                .executeUpdate();

        LOG.info("Data retention cleanup complete: hourly(auth={}, revenue={}), daily(auth={}, revenue={}, decline={}), monthly(auth={}), health={}",
                hourlyAuth, hourlyRevenue, dailyAuth, dailyRevenue, dailyDecline, monthlyAuth, healthSnapshots);
    }
}
