package io.nexuspay.fraud.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.fraud.application.port.out.FraudAssessmentRepository;
import io.nexuspay.fraud.domain.model.RiskAssessment;
import io.nexuspay.fraud.domain.model.RiskDecision;
import io.nexuspay.fraud.domain.model.RiskSignal;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter mapping between RiskAssessment domain and FraudAssessmentEntity JPA entity.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
@Component
public class FraudAssessmentRepositoryAdapter implements FraudAssessmentRepository {

    private final JpaFraudAssessmentRepository jpaRepo;
    private final ObjectMapper objectMapper;

    public FraudAssessmentRepositoryAdapter(JpaFraudAssessmentRepository jpaRepo,
                                             ObjectMapper objectMapper) {
        this.jpaRepo = jpaRepo;
        this.objectMapper = objectMapper;
    }

    @Override
    public RiskAssessment save(RiskAssessment assessment) {
        FraudAssessmentEntity entity = toEntity(assessment);
        FraudAssessmentEntity saved = jpaRepo.save(entity);
        return toDomain(saved);
    }

    @Override
    public RiskAssessment saveAndFlush(RiskAssessment assessment) {
        // B-027b: flush synchronously so the uq_fraud_assessments_tenant_idem violation is raised
        // HERE (inside assess()'s try/catch) instead of being deferred to commit/outbox flush.
        FraudAssessmentEntity entity = toEntity(assessment);
        FraudAssessmentEntity saved = jpaRepo.saveAndFlush(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<RiskAssessment> findById(UUID id) {
        return jpaRepo.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<RiskAssessment> findByPaymentId(String paymentId) {
        return jpaRepo.findByPaymentId(paymentId).map(this::toDomain);
    }

    @Override
    public Optional<RiskAssessment> findByTenantIdAndPaymentId(String tenantId, String paymentId) {
        return jpaRepo.findByTenantIdAndPaymentId(tenantId, paymentId).map(this::toDomain);
    }

    @Override
    public Optional<RiskAssessment> findByIdAndTenantId(UUID id, String tenantId) {
        return jpaRepo.findByIdAndTenantId(id, tenantId).map(this::toDomain);
    }

    @Override
    public List<RiskAssessment> findPendingReviews(String tenantId, int limit) {
        return jpaRepo.findByTenantIdAndReviewStatus(tenantId, "PENDING_REVIEW",
                        PageRequest.of(0, limit))
                .stream().map(this::toDomain).toList();
    }

    private FraudAssessmentEntity toEntity(RiskAssessment a) {
        FraudAssessmentEntity e = new FraudAssessmentEntity();
        e.setId(a.getId());
        e.setTenantId(a.getTenantId());
        e.setPaymentId(a.getPaymentId());
        e.setNativeScore(a.getNativeScore());
        e.setFrmScore(a.getFrmScore());
        e.setFrmProvider(a.getFrmProvider());
        e.setAggregatedScore(a.getAggregatedScore());
        e.setDecision(a.getDecision().name());
        try {
            e.setTriggeredRules(objectMapper.writeValueAsString(a.getTriggeredRuleIds()));
            e.setRiskSignals(objectMapper.writeValueAsString(a.getRiskSignals()));
        } catch (Exception ex) {
            throw new RuntimeException("Failed to serialize assessment data", ex);
        }
        e.setReviewStatus(a.getReviewStatus());
        e.setReviewedBy(a.getReviewedBy());
        e.setReviewedAt(a.getReviewedAt());
        e.setAssessedAt(a.getAssessedAt());
        e.setLatencyMs(a.getLatencyMs());
        e.setRequestFingerprint(a.getRequestFingerprint()); // B-029-hardening: persist on write
        return e;
    }

    private RiskAssessment toDomain(FraudAssessmentEntity e) {
        RiskAssessment a = new RiskAssessment();
        a.setId(e.getId());
        a.setTenantId(e.getTenantId());
        a.setPaymentId(e.getPaymentId());
        a.setNativeScore(e.getNativeScore());
        a.setFrmScore(e.getFrmScore());
        a.setFrmProvider(e.getFrmProvider());
        a.setAggregatedScore(e.getAggregatedScore());
        a.setDecision(RiskDecision.valueOf(e.getDecision()));
        try {
            a.setTriggeredRuleIds(objectMapper.readValue(
                    e.getTriggeredRules(), new TypeReference<List<String>>() {}));
            a.setRiskSignals(objectMapper.readValue(
                    e.getRiskSignals(), new TypeReference<List<RiskSignal>>() {}));
        } catch (Exception ex) {
            throw new RuntimeException("Failed to deserialize assessment data", ex);
        }
        a.setReviewStatus(e.getReviewStatus());
        a.setReviewedBy(e.getReviewedBy());
        a.setReviewedAt(e.getReviewedAt());
        a.setAssessedAt(e.getAssessedAt());
        a.setLatencyMs(e.getLatencyMs());
        a.setRequestFingerprint(e.getRequestFingerprint()); // B-029-hardening: available on dedup-hit read
        return a;
    }
}
