package io.nexuspay.analytics.adapter.out.persistence;

import io.nexuspay.analytics.application.port.out.DeclineRollupRepository;
import io.nexuspay.analytics.domain.model.DeclineAnalysis;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Repository adapter for the decline rollup port.
 *
 * <p>Kept separate from {@link AnalyticsRepositoryAdapters}: this port's
 * {@code findDaily} has the same parameter types as the auth-rate port's but a
 * different return type, so one class cannot implement both.</p>
 *
 * @since 0.3.0 (Sprint 3.6)
 */
@Component
public class DeclineRollupRepositoryAdapter implements DeclineRollupRepository {

    private final JpaDeclineDailyRepository declineDailyRepo;
    private final EntityManager entityManager;

    public DeclineRollupRepositoryAdapter(JpaDeclineDailyRepository declineDailyRepo,
                                          EntityManager entityManager) {
        this.declineDailyRepo = declineDailyRepo;
        this.entityManager = entityManager;
    }

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

    private DeclineDailyEntity toDeclineEntity(DeclineAnalysis d) {
        return new DeclineDailyEntity(null, d.tenantId(), d.bucketDate(), d.pspConnector(),
                d.declineCode(), d.declineCategory(), d.cardBrand(), d.issuingRegion(),
                d.issuerName(), d.totalCount(), d.totalVolume());
    }

    private DeclineAnalysis fromDeclineEntity(DeclineDailyEntity e) {
        return new DeclineAnalysis(e.getTenantId(), e.getBucketDate(), e.getPspConnector(),
                e.getDeclineCode(), e.getDeclineCategory(), e.getCardBrand(), e.getIssuingRegion(),
                e.getIssuerName(), e.getTotalCount(), e.getTotalVolume());
    }
}
