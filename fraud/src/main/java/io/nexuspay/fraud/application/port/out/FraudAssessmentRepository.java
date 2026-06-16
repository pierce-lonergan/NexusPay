package io.nexuspay.fraud.application.port.out;

import io.nexuspay.fraud.domain.model.RiskAssessment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository port for fraud assessment persistence.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
public interface FraudAssessmentRepository {

    RiskAssessment save(RiskAssessment assessment);

    /**
     * Persists and FLUSHES the assessment synchronously, so a unique-constraint violation surfaces
     * at THIS call rather than being deferred to transaction commit / outbox flush.
     *
     * <p>B-027b race backstop relies on this: {@code FraudAssessmentEntity} has a pre-assigned
     * {@code UUID @Id} (no {@code @GeneratedValue}/{@code @Version}, not {@code Persistable}), so
     * {@code SimpleJpaRepository.save()} does {@code em.merge()} and DEFERS the INSERT to
     * flush/commit. The duplicate-key violation from the {@code uq_fraud_assessments_tenant_idem}
     * index would then escape {@code assess()}'s try/catch (raised at commit / event-outbox flush)
     * and propagate, with a duplicate event possibly attempted. Flushing here makes the INSERT (and
     * thus the constraint check) happen INSIDE the catch so the loser is handled deterministically.
     */
    RiskAssessment saveAndFlush(RiskAssessment assessment);

    Optional<RiskAssessment> findById(UUID id);

    Optional<RiskAssessment> findByPaymentId(String paymentId);

    /**
     * Tenant-scoped lookup of a prior assessment by its payment id (== idempotency key on the gate
     * path). The dedup key for B-027b idempotent {@code assess()}: a retry of the SAME request
     * returns the prior assessment instead of re-running the pipeline. Tenant-scoped (not bare
     * {@code findByPaymentId}) so dedup is correct under multi-tenant key collisions and aligns
     * with RLS.
     */
    Optional<RiskAssessment> findByTenantIdAndPaymentId(String tenantId, String paymentId);

    /** SEC-23: tenant-scoped by-id lookup — empty when absent OR owned by another tenant. */
    Optional<RiskAssessment> findByIdAndTenantId(UUID id, String tenantId);

    List<RiskAssessment> findPendingReviews(String tenantId, int limit);
}
