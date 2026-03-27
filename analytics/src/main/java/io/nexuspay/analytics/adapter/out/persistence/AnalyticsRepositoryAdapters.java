package io.nexuspay.analytics.adapter.out.persistence;

import io.nexuspay.analytics.application.port.out.AuthRateRollupRepository;
import io.nexuspay.analytics.application.port.out.DeclineRollupRepository;
import io.nexuspay.analytics.application.port.out.PspHealthRepository;
import io.nexuspay.analytics.application.port.out.RevenueRollupRepository;
import io.nexuspay.analytics.domain.model.AuthRateMetric;
import io.nexuspay.analytics.domain.model.DeclineAnalysis;
import io.nexuspay.analytics.domain.model.PspHealthScore;
import io.nexuspay.analytics.domain.model.RevenueMetric;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Repository adapter implementing all analytics out-port repository interfaces.
 * Maps between domain models and JPA entities.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
@Component
public class AnalyticsRepositoryAdapters implements AuthRateRollupRepository, PspHealthRepository,
        RevenueRollupRepository, DeclineRollupRepository {

    private static final Logger LOG = LoggerFactory.getLogger(AnalyticsRepositoryAdapters.class);

    private final JpaAuthRateHourlyRepository authRateHourlyRepo;
    private final JpaAuthRateDailyRepository authRateDailyRepo;
    private final JpaAuthRateMonthlyRepository authRateMonthlyRepo;
    private final JpaPspHealthSnapshotRepository pspHealthRepo;
    private final JpaRevenueHourlyRepository revenueHourlyRepo;
    private final JpaRevenueDailyRepository revenueDailyRepo;
    private final JpaDeclineDailyRepository declineDailyRepo;
    private final EntityManager entityManager;

    public AnalyticsRepositoryAdapters(
            JpaAuthRateHourlyRepository authRateHourlyRepo,
            JpaAuthRateDailyRepository authRateDailyRepo,
            JpaAuthRateMonthlyRepository authRateMonthlyRepo,
            JpaPspHealthSnapshotRepository pspHealthRepo,
            JpaRevenueHourlyRepository revenueHourlyRepo,
            JpaRevenueDailyRepository revenueDailyRepo,
            JpaDeclineDailyRepository declineDailyRepo,
            EntityManager entityManager) {
        this.authRateHourlyRepo = authRateHourlyRepo;
        this.authRateDailyRepo = authRateDailyRepo;
        this.authRateMonthlyRepo = authRateMonthlyRepo;
        this.pspHealthRepo = pspHealthRepo;
        this.revenueHourlyRepo = revenueHourlyRepo;
        this.revenueDailyRepo = revenueDailyRepo;
        this.declineDailyRepo = declineDailyRepo;
        this.entityManager = entityManager;
    }

    // --- AuthRateRollupRepository ---

    @Override
    public void saveHourly(AuthRateMetric metric) {
        authRateHourlyRepo.save(toHourlyEntity(metric));
    }

    @Override
    public void saveDaily(AuthRateMetric metric) {
        authRateDailyRepo.save(toDailyEntity(metric));
    }

    @Override
    public void saveMonthly(AuthRateMetric metric) {
        authRateMonthlyRepo.save(toMonthlyEntity(metric));
    }

    @Override
    public List<AuthRateMetric> findHourly(String tenantId, Instant from, Instant to,
                                            String pspConnector, String cardBrand, String currency) {
        List<AuthRateHourlyEntity> entities;
        if (pspConnector != null) {
            entities = authRateHourlyRepo.findByTenantIdAndBucketHourBetweenAndPspConnector(
                    tenantId, from, to, pspConnector);
        } else {
            entities = authRateHourlyRepo.findByTenantIdAndBucketHourBetween(tenantId, from, to);
        }
        return entities.stream().map(this::fromHourlyEntity).toList();
    }

    @Override
    public List<AuthRateMetric> findDaily(String tenantId, LocalDate from, LocalDate to,
                                           String pspConnector, String cardBrand, String currency) {
        List<AuthRateDailyEntity> entities;
        if (pspConnector != null) {
            entities = authRateDailyRepo.findByTenantIdAndBucketDateBetweenAndPspConnector(
                    tenantId, from, to, pspConnector);
        } else {
            entities = authRateDailyRepo.findByTenantIdAndBucketDateBetween(tenantId, from, to);
        }
        return entities.stream().map(this::fromDailyEntity).toList();
    }

    @Override
    public List<AuthRateMetric> findMonthly(String tenantId, LocalDate from, LocalDate to,
                                             String pspConnector, String cardBrand, String currency) {
        List<AuthRateMonthlyEntity> entities;
        if (pspConnector != null) {
            entities = authRateMonthlyRepo.findByTenantIdAndBucketMonthBetweenAndPspConnector(
                    tenantId, from, to, pspConnector);
        } else {
            entities = authRateMonthlyRepo.findByTenantIdAndBucketMonthBetween(tenantId, from, to);
        }
        return entities.stream().map(this::fromMonthlyEntity).toList();
    }

    @Override
    public void upsertHourly(AuthRateMetric metric) {
        Query query = entityManager.createNativeQuery("""
                INSERT INTO analytics.auth_rate_hourly
                    (id, tenant_id, bucket_hour, psp_connector, card_brand, card_type,
                     issuing_region, currency, payment_method, total_attempts, total_approved,
                     total_declined, total_errors, auth_rate, avg_latency_ms, p50_latency_ms,
                     p95_latency_ms, p99_latency_ms)
                VALUES (gen_random_uuid(), :tenantId, :bucketHour, :pspConnector, :cardBrand, :cardType,
                        :issuingRegion, :currency, :paymentMethod, :totalAttempts, :totalApproved,
                        :totalDeclined, :totalErrors, :authRate, :avgLatencyMs, :p50LatencyMs,
                        :p95LatencyMs, :p99LatencyMs)
                ON CONFLICT (tenant_id, bucket_hour, psp_connector, card_brand, card_type,
                             issuing_region, currency, payment_method)
                DO UPDATE SET
                    total_attempts = auth_rate_hourly.total_attempts + EXCLUDED.total_attempts,
                    total_approved = auth_rate_hourly.total_approved + EXCLUDED.total_approved,
                    total_declined = auth_rate_hourly.total_declined + EXCLUDED.total_declined,
                    total_errors = auth_rate_hourly.total_errors + EXCLUDED.total_errors,
                    auth_rate = CASE WHEN (auth_rate_hourly.total_attempts + EXCLUDED.total_attempts) > 0
                        THEN (auth_rate_hourly.total_approved + EXCLUDED.total_approved)::DECIMAL
                             / (auth_rate_hourly.total_attempts + EXCLUDED.total_attempts)
                        ELSE 0 END,
                    avg_latency_ms = COALESCE(EXCLUDED.avg_latency_ms, auth_rate_hourly.avg_latency_ms),
                    p50_latency_ms = COALESCE(EXCLUDED.p50_latency_ms, auth_rate_hourly.p50_latency_ms),
                    p95_latency_ms = COALESCE(EXCLUDED.p95_latency_ms, auth_rate_hourly.p95_latency_ms),
                    p99_latency_ms = COALESCE(EXCLUDED.p99_latency_ms, auth_rate_hourly.p99_latency_ms)
                """);
        setAuthRateParams(query, metric);
        query.executeUpdate();
    }

    // --- PspHealthRepository ---

    @Override
    public void save(PspHealthScore score) {
        pspHealthRepo.save(toHealthEntity(score));
    }

    @Override
    public Optional<PspHealthScore> findLatest(String tenantId, String pspConnector) {
        return pspHealthRepo.findLatest(tenantId, pspConnector).map(this::fromHealthEntity);
    }

    @Override
    public List<PspHealthScore> findAllLatest(String tenantId) {
        return pspHealthRepo.findAllLatest(tenantId).stream().map(this::fromHealthEntity).toList();
    }

    @Override
    public List<PspHealthScore> findTrend(String tenantId, String pspConnector, Instant from, Instant to) {
        return pspHealthRepo.findByTenantIdAndPspConnectorAndSnapshotTimeBetween(
                tenantId, pspConnector, from, to).stream().map(this::fromHealthEntity).toList();
    }

    // --- RevenueRollupRepository ---

    @Override
    public void saveHourly(RevenueMetric metric) {
        revenueHourlyRepo.save(toRevenueHourlyEntity(metric));
    }

    @Override
    public void saveDaily(RevenueMetric metric) {
        revenueDailyRepo.save(toRevenueDailyEntity(metric));
    }

    @Override
    public List<RevenueMetric> findHourly(String tenantId, Instant from, Instant to,
                                           String pspConnector, String currency) {
        return revenueHourlyRepo.findByTenantIdAndBucketHourBetween(tenantId, from, to)
                .stream().map(this::fromRevenueHourlyEntity).toList();
    }

    @Override
    public List<RevenueMetric> findDaily(String tenantId, LocalDate from, LocalDate to,
                                          String pspConnector, String currency) {
        return revenueDailyRepo.findByTenantIdAndBucketDateBetween(tenantId, from, to)
                .stream().map(this::fromRevenueDailyEntity).toList();
    }

    @Override
    public void upsertHourly(RevenueMetric metric) {
        Query query = entityManager.createNativeQuery("""
                INSERT INTO analytics.revenue_hourly
                    (id, tenant_id, bucket_hour, psp_connector, currency, payment_method,
                     total_volume, total_count, total_fees, net_revenue,
                     refund_volume, refund_count, chargeback_volume, chargeback_count)
                VALUES (gen_random_uuid(), :tenantId, :bucketHour, :pspConnector, :currency, :paymentMethod,
                        :totalVolume, :totalCount, :totalFees, :netRevenue,
                        :refundVolume, :refundCount, :chargebackVolume, :chargebackCount)
                ON CONFLICT (tenant_id, bucket_hour, psp_connector, currency, payment_method)
                DO UPDATE SET
                    total_volume = revenue_hourly.total_volume + EXCLUDED.total_volume,
                    total_count = revenue_hourly.total_count + EXCLUDED.total_count,
                    total_fees = revenue_hourly.total_fees + EXCLUDED.total_fees,
                    net_revenue = revenue_hourly.net_revenue + EXCLUDED.net_revenue,
                    refund_volume = revenue_hourly.refund_volume + EXCLUDED.refund_volume,
                    refund_count = revenue_hourly.refund_count + EXCLUDED.refund_count,
                    chargeback_volume = revenue_hourly.chargeback_volume + EXCLUDED.chargeback_volume,
                    chargeback_count = revenue_hourly.chargeback_count + EXCLUDED.chargeback_count
                """);
        query.setParameter("tenantId", metric.tenantId());
        query.setParameter("bucketHour", metric.bucketTime());
        query.setParameter("pspConnector", metric.pspConnector());
        query.setParameter("currency", metric.currency());
        query.setParameter("paymentMethod", metric.paymentMethod());
        query.setParameter("totalVolume", metric.totalVolume());
        query.setParameter("totalCount", metric.totalCount());
        query.setParameter("totalFees", metric.totalFees());
        query.setParameter("netRevenue", metric.netRevenue());
        query.setParameter("refundVolume", metric.refundVolume());
        query.setParameter("refundCount", metric.refundCount());
        query.setParameter("chargebackVolume", metric.chargebackVolume());
        query.setParameter("chargebackCount", metric.chargebackCount());
        query.executeUpdate();
    }

    // --- DeclineRollupRepository ---

    @Override
    public void saveDaily(DeclineAnalysis decline) {
        declineDailyRepo.save(toDeclineEntity(decline));
    }

    @Override
    public List<DeclineAnalysis> findDaily(String tenantId, LocalDate from, LocalDate to,
                                            String pspConnector, String declineCode, String cardBrand) {
        List<DeclineDailyEntity> entities;
        if (pspConnector != null) {
            entities = declineDailyRepo.findByTenantIdAndBucketDateBetweenAndPspConnector(
                    tenantId, from, to, pspConnector);
        } else {
            entities = declineDailyRepo.findByTenantIdAndBucketDateBetween(tenantId, from, to);
        }
        return entities.stream().map(this::fromDeclineEntity).toList();
    }

    @Override
    public void upsertDaily(DeclineAnalysis decline) {
        Query query = entityManager.createNativeQuery("""
                INSERT INTO analytics.decline_daily
                    (id, tenant_id, bucket_date, psp_connector, decline_code, decline_category,
                     card_brand, issuing_region, issuer_name, total_count, total_volume)
                VALUES (gen_random_uuid(), :tenantId, :bucketDate, :pspConnector, :declineCode,
                        :declineCategory, :cardBrand, :issuingRegion, :issuerName,
                        :totalCount, :totalVolume)
                ON CONFLICT (tenant_id, bucket_date, psp_connector, decline_code, card_brand,
                             issuing_region, issuer_name)
                DO UPDATE SET
                    total_count = decline_daily.total_count + EXCLUDED.total_count,
                    total_volume = decline_daily.total_volume + EXCLUDED.total_volume
                """);
        query.setParameter("tenantId", decline.tenantId());
        query.setParameter("bucketDate", decline.bucketDate());
        query.setParameter("pspConnector", decline.pspConnector());
        query.setParameter("declineCode", decline.declineCode());
        query.setParameter("declineCategory", decline.declineCategory());
        query.setParameter("cardBrand", decline.cardBrand());
        query.setParameter("issuingRegion", decline.issuingRegion());
        query.setParameter("issuerName", decline.issuerName());
        query.setParameter("totalCount", decline.totalCount());
        query.setParameter("totalVolume", decline.totalVolume());
        query.executeUpdate();
    }

    // --- Entity ↔ Domain mapping helpers ---

    private void setAuthRateParams(Query query, AuthRateMetric m) {
        query.setParameter("tenantId", m.tenantId());
        query.setParameter("bucketHour", m.bucketTime());
        query.setParameter("pspConnector", m.pspConnector());
        query.setParameter("cardBrand", m.cardBrand());
        query.setParameter("cardType", m.cardType());
        query.setParameter("issuingRegion", m.issuingRegion());
        query.setParameter("currency", m.currency());
        query.setParameter("paymentMethod", m.paymentMethod());
        query.setParameter("totalAttempts", m.totalAttempts());
        query.setParameter("totalApproved", m.totalApproved());
        query.setParameter("totalDeclined", m.totalDeclined());
        query.setParameter("totalErrors", m.totalErrors());
        query.setParameter("authRate", m.authRate());
        query.setParameter("avgLatencyMs", m.avgLatencyMs());
        query.setParameter("p50LatencyMs", m.p50LatencyMs());
        query.setParameter("p95LatencyMs", m.p95LatencyMs());
        query.setParameter("p99LatencyMs", m.p99LatencyMs());
    }

    private AuthRateHourlyEntity toHourlyEntity(AuthRateMetric m) {
        return new AuthRateHourlyEntity(m.tenantId(), m.bucketTime(), m.pspConnector(),
                m.cardBrand(), m.cardType(), m.issuingRegion(), m.currency(), m.paymentMethod(),
                m.totalAttempts(), m.totalApproved(), m.totalDeclined(), m.totalErrors(),
                m.authRate(), m.avgLatencyMs(), m.p50LatencyMs(), m.p95LatencyMs(), m.p99LatencyMs());
    }

    private AuthRateMetric fromHourlyEntity(AuthRateHourlyEntity e) {
        return new AuthRateMetric(e.getTenantId(), e.getBucketHour(), e.getPspConnector(),
                e.getCardBrand(), e.getCardType(), e.getIssuingRegion(), e.getCurrency(),
                e.getPaymentMethod(), e.getTotalAttempts(), e.getTotalApproved(), e.getTotalDeclined(),
                e.getTotalErrors(), e.getAuthRate(), e.getAvgLatencyMs(), e.getP50LatencyMs(),
                e.getP95LatencyMs(), e.getP99LatencyMs());
    }

    private AuthRateDailyEntity toDailyEntity(AuthRateMetric m) {
        return new AuthRateDailyEntity(m.tenantId(),
                m.bucketTime().atZone(ZoneOffset.UTC).toLocalDate(), m.pspConnector(),
                m.cardBrand(), m.cardType(), m.issuingRegion(), m.currency(), m.paymentMethod(),
                m.totalAttempts(), m.totalApproved(), m.totalDeclined(), m.totalErrors(),
                m.authRate(), m.avgLatencyMs(), m.p95LatencyMs());
    }

    private AuthRateMetric fromDailyEntity(AuthRateDailyEntity e) {
        return new AuthRateMetric(e.getTenantId(),
                e.getBucketDate().atStartOfDay(ZoneOffset.UTC).toInstant(), e.getPspConnector(),
                e.getCardBrand(), e.getCardType(), e.getIssuingRegion(), e.getCurrency(),
                e.getPaymentMethod(), e.getTotalAttempts(), e.getTotalApproved(), e.getTotalDeclined(),
                e.getTotalErrors(), e.getAuthRate(), e.getAvgLatencyMs(), null,
                e.getP95LatencyMs(), null);
    }

    private AuthRateMonthlyEntity toMonthlyEntity(AuthRateMetric m) {
        return new AuthRateMonthlyEntity(m.tenantId(),
                m.bucketTime().atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1), m.pspConnector(),
                m.cardBrand(), m.cardType(), m.issuingRegion(), m.currency(), m.paymentMethod(),
                m.totalAttempts(), m.totalApproved(), m.totalDeclined(), m.totalErrors(), m.authRate());
    }

    private AuthRateMetric fromMonthlyEntity(AuthRateMonthlyEntity e) {
        return new AuthRateMetric(e.getTenantId(),
                e.getBucketMonth().atStartOfDay(ZoneOffset.UTC).toInstant(), e.getPspConnector(),
                e.getCardBrand(), e.getCardType(), e.getIssuingRegion(), e.getCurrency(),
                e.getPaymentMethod(), e.getTotalAttempts(), e.getTotalApproved(), e.getTotalDeclined(),
                e.getTotalErrors(), e.getAuthRate(), null, null, null, null);
    }

    private PspHealthSnapshotEntity toHealthEntity(PspHealthScore s) {
        return new PspHealthSnapshotEntity(s.tenantId(), s.pspConnector(), s.snapshotTime(),
                s.healthScore(), s.authRateScore(), s.latencyScore(), s.errorRateScore(),
                s.authRate7d(), s.avgLatencyMs(), s.p95LatencyMs(), s.errorRate(),
                s.anomalyDetected(), s.anomalyDetails() != null ? s.anomalyDetails().toString() : null);
    }

    private PspHealthScore fromHealthEntity(PspHealthSnapshotEntity e) {
        return new PspHealthScore(e.getTenantId(), e.getPspConnector(), e.getSnapshotTime(),
                e.getHealthScore(), e.getAuthRateScore(), e.getLatencyScore(), e.getErrorRateScore(),
                e.getAuthRate7d(), e.getAvgLatencyMs(), e.getP95LatencyMs(), e.getErrorRate(),
                e.isAnomalyDetected(), Collections.emptyMap());
    }

    private RevenueHourlyEntity toRevenueHourlyEntity(RevenueMetric m) {
        return new RevenueHourlyEntity(m.tenantId(), m.bucketTime(), m.pspConnector(),
                m.currency(), m.paymentMethod(), m.totalVolume(), m.totalCount(), m.totalFees(),
                m.netRevenue(), m.refundVolume(), m.refundCount(), m.chargebackVolume(), m.chargebackCount());
    }

    private RevenueMetric fromRevenueHourlyEntity(RevenueHourlyEntity e) {
        return new RevenueMetric(e.getTenantId(), e.getBucketHour(), e.getPspConnector(),
                e.getCurrency(), e.getPaymentMethod(), e.getTotalVolume(), e.getTotalCount(),
                e.getTotalFees(), e.getNetRevenue(), e.getRefundVolume(), e.getRefundCount(),
                e.getChargebackVolume(), e.getChargebackCount());
    }

    private RevenueDailyEntity toRevenueDailyEntity(RevenueMetric m) {
        return new RevenueDailyEntity(m.tenantId(),
                m.bucketTime().atZone(ZoneOffset.UTC).toLocalDate(), m.pspConnector(),
                m.currency(), m.paymentMethod(), m.totalVolume(), m.totalCount(), m.totalFees(),
                m.netRevenue(), m.refundVolume(), m.refundCount(), m.chargebackVolume(), m.chargebackCount());
    }

    private RevenueMetric fromRevenueDailyEntity(RevenueDailyEntity e) {
        return new RevenueMetric(e.getTenantId(),
                e.getBucketDate().atStartOfDay(ZoneOffset.UTC).toInstant(), e.getPspConnector(),
                e.getCurrency(), e.getPaymentMethod(), e.getTotalVolume(), e.getTotalCount(),
                e.getTotalFees(), e.getNetRevenue(), e.getRefundVolume(), e.getRefundCount(),
                e.getChargebackVolume(), e.getChargebackCount());
    }

    private DeclineDailyEntity toDeclineEntity(DeclineAnalysis d) {
        return new DeclineDailyEntity(d.tenantId(), d.bucketDate(), d.pspConnector(),
                d.declineCode(), d.declineCategory(), d.cardBrand(), d.issuingRegion(),
                d.issuerName(), d.totalCount(), d.totalVolume());
    }

    private DeclineAnalysis fromDeclineEntity(DeclineDailyEntity e) {
        return new DeclineAnalysis(e.getTenantId(), e.getBucketDate(), e.getPspConnector(),
                e.getDeclineCode(), e.getDeclineCategory(), e.getCardBrand(), e.getIssuingRegion(),
                e.getIssuerName(), e.getTotalCount(), e.getTotalVolume());
    }
}
