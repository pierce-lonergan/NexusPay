package io.nexuspay.analytics.application.service;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled jobs for rolling up analytics data from finer to coarser granularity.
 * Uses idempotent INSERT ... ON CONFLICT UPDATE statements.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
@Service
public class RollupJobService {

    private static final Logger LOG = LoggerFactory.getLogger(RollupJobService.class);

    private final EntityManager entityManager;

    public RollupJobService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Roll up hourly auth rate data into daily aggregates.
     * Runs daily at 00:05 UTC.
     */
    @Scheduled(cron = "${nexuspay.analytics.rollup.daily-rollup-cron:0 5 0 * * *}")
    @Transactional
    public void rollupAuthRateHourlyToDaily() {
        LOG.info("Starting auth rate hourly → daily rollup");
        int rows = entityManager.createNativeQuery("""
                INSERT INTO analytics.auth_rate_daily
                    (id, tenant_id, bucket_date, psp_connector, card_brand, card_type,
                     issuing_region, currency, payment_method, total_attempts, total_approved,
                     total_declined, total_errors, auth_rate, avg_latency_ms, p95_latency_ms)
                SELECT gen_random_uuid(), tenant_id, DATE(bucket_hour), psp_connector, card_brand,
                       card_type, issuing_region, currency, payment_method,
                       SUM(total_attempts), SUM(total_approved), SUM(total_declined), SUM(total_errors),
                       CASE WHEN SUM(total_attempts) > 0
                            THEN SUM(total_approved)::DECIMAL / SUM(total_attempts)
                            ELSE 0 END,
                       AVG(avg_latency_ms)::INTEGER, MAX(p95_latency_ms)
                FROM analytics.auth_rate_hourly
                WHERE DATE(bucket_hour) = CURRENT_DATE - INTERVAL '1 day'
                GROUP BY tenant_id, DATE(bucket_hour), psp_connector, card_brand, card_type,
                         issuing_region, currency, payment_method
                ON CONFLICT (tenant_id, bucket_date, psp_connector, card_brand, card_type,
                             issuing_region, currency, payment_method)
                DO UPDATE SET
                    total_attempts = EXCLUDED.total_attempts,
                    total_approved = EXCLUDED.total_approved,
                    total_declined = EXCLUDED.total_declined,
                    total_errors = EXCLUDED.total_errors,
                    auth_rate = EXCLUDED.auth_rate,
                    avg_latency_ms = EXCLUDED.avg_latency_ms,
                    p95_latency_ms = EXCLUDED.p95_latency_ms
                """).executeUpdate();
        LOG.info("Auth rate hourly → daily rollup complete: {} rows", rows);
    }

    /**
     * Roll up daily auth rate data into monthly aggregates.
     * Runs on the 1st of each month at 00:10 UTC.
     */
    @Scheduled(cron = "${nexuspay.analytics.rollup.monthly-rollup-cron:0 10 0 1 * *}")
    @Transactional
    public void rollupAuthRateDailyToMonthly() {
        LOG.info("Starting auth rate daily → monthly rollup");
        int rows = entityManager.createNativeQuery("""
                INSERT INTO analytics.auth_rate_monthly
                    (id, tenant_id, bucket_month, psp_connector, card_brand, card_type,
                     issuing_region, currency, payment_method, total_attempts, total_approved,
                     total_declined, total_errors, auth_rate)
                SELECT gen_random_uuid(), tenant_id,
                       DATE_TRUNC('month', bucket_date)::DATE, psp_connector, card_brand,
                       card_type, issuing_region, currency, payment_method,
                       SUM(total_attempts), SUM(total_approved), SUM(total_declined), SUM(total_errors),
                       CASE WHEN SUM(total_attempts) > 0
                            THEN SUM(total_approved)::DECIMAL / SUM(total_attempts)
                            ELSE 0 END
                FROM analytics.auth_rate_daily
                WHERE bucket_date >= DATE_TRUNC('month', CURRENT_DATE - INTERVAL '1 month')
                  AND bucket_date < DATE_TRUNC('month', CURRENT_DATE)
                GROUP BY tenant_id, DATE_TRUNC('month', bucket_date)::DATE, psp_connector,
                         card_brand, card_type, issuing_region, currency, payment_method
                ON CONFLICT (tenant_id, bucket_month, psp_connector, card_brand, card_type,
                             issuing_region, currency, payment_method)
                DO UPDATE SET
                    total_attempts = EXCLUDED.total_attempts,
                    total_approved = EXCLUDED.total_approved,
                    total_declined = EXCLUDED.total_declined,
                    total_errors = EXCLUDED.total_errors,
                    auth_rate = EXCLUDED.auth_rate
                """).executeUpdate();
        LOG.info("Auth rate daily → monthly rollup complete: {} rows", rows);
    }

    /**
     * Roll up hourly revenue data into daily aggregates.
     * Runs daily at 00:05 UTC (same schedule as auth rate rollup).
     */
    @Scheduled(cron = "${nexuspay.analytics.rollup.daily-rollup-cron:0 5 0 * * *}")
    @Transactional
    public void rollupRevenueHourlyToDaily() {
        LOG.info("Starting revenue hourly → daily rollup");
        int rows = entityManager.createNativeQuery("""
                INSERT INTO analytics.revenue_daily
                    (id, tenant_id, bucket_date, psp_connector, currency, payment_method,
                     total_volume, total_count, total_fees, net_revenue,
                     refund_volume, refund_count, chargeback_volume, chargeback_count)
                SELECT gen_random_uuid(), tenant_id, DATE(bucket_hour), psp_connector,
                       currency, payment_method,
                       SUM(total_volume), SUM(total_count), SUM(total_fees), SUM(net_revenue),
                       SUM(refund_volume), SUM(refund_count), SUM(chargeback_volume), SUM(chargeback_count)
                FROM analytics.revenue_hourly
                WHERE DATE(bucket_hour) = CURRENT_DATE - INTERVAL '1 day'
                GROUP BY tenant_id, DATE(bucket_hour), psp_connector, currency, payment_method
                ON CONFLICT (tenant_id, bucket_date, psp_connector, currency, payment_method)
                DO UPDATE SET
                    total_volume = EXCLUDED.total_volume,
                    total_count = EXCLUDED.total_count,
                    total_fees = EXCLUDED.total_fees,
                    net_revenue = EXCLUDED.net_revenue,
                    refund_volume = EXCLUDED.refund_volume,
                    refund_count = EXCLUDED.refund_count,
                    chargeback_volume = EXCLUDED.chargeback_volume,
                    chargeback_count = EXCLUDED.chargeback_count
                """).executeUpdate();
        LOG.info("Revenue hourly → daily rollup complete: {} rows", rows);
    }
}
