package io.nexuspay.gateway.adapter.out.persistence;

import io.nexuspay.gateway.application.port.out.PaymentSessionRepository;
import io.nexuspay.gateway.domain.PaymentSession;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Adapts the Spring Data JPA repository to the domain port interface.
 *
 * @since 0.3.5 (Sprint 3.5)
 */
@Repository
public class PaymentSessionRepositoryAdapter implements PaymentSessionRepository {

    private final JpaPaymentSessionRepository jpa;

    public PaymentSessionRepositoryAdapter(JpaPaymentSessionRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public PaymentSession save(PaymentSession session) {
        var entity = toEntity(session);
        jpa.save(entity);
        return session;
    }

    @Override
    public Optional<PaymentSession> findById(String id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<PaymentSession> findByClientSecret(String clientSecret) {
        return jpa.findByClientSecret(clientSecret).map(this::toDomain);
    }

    @Override
    @Transactional
    public void updateStatus(String id, String status) {
        jpa.updateStatus(id, status);
    }

    @Override
    @Transactional
    public int incrementTokenizeAttempts(String id) {
        jpa.incrementTokenizeAttempts(id);
        // Return the updated count by re-reading
        return jpa.findById(id)
                .map(PaymentSessionEntity::getTokenizeAttempts)
                .orElse(0);
    }

    private PaymentSessionEntity toEntity(PaymentSession s) {
        return new PaymentSessionEntity(
                s.getId(), s.getTenantId(), s.getPaymentIntentId(), s.getClientSecret(),
                s.getAmount(), s.getCurrency(), s.getStatus(), s.getCustomerId(),
                s.getAllowedPaymentMethods(), s.getSuccessUrl(), s.getCancelUrl(),
                s.getBranding(), s.getMetadata(), s.getTokenizeAttempts(),
                s.getExpiresAt(), s.getCreatedAt(), s.getUpdatedAt()
        );
    }

    private PaymentSession toDomain(PaymentSessionEntity e) {
        return new PaymentSession(
                e.getId(), e.getTenantId(), e.getPaymentIntentId(), e.getClientSecret(),
                e.getAmount(), e.getCurrency(), e.getStatus(), e.getCustomerId(),
                e.getAllowedPaymentMethods(), e.getSuccessUrl(), e.getCancelUrl(),
                e.getBranding(), e.getMetadata(), e.getTokenizeAttempts(),
                e.getExpiresAt(), e.getCreatedAt(), e.getUpdatedAt()
        );
    }
}
