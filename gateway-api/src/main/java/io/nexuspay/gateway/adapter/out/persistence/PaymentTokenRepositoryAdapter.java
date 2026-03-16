package io.nexuspay.gateway.adapter.out.persistence;

import io.nexuspay.gateway.application.port.out.PaymentTokenRepository;
import io.nexuspay.gateway.domain.PaymentToken;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Adapts the Spring Data JPA repository to the domain port interface.
 *
 * @since 0.3.5 (Sprint 3.5)
 */
@Repository
public class PaymentTokenRepositoryAdapter implements PaymentTokenRepository {

    private final JpaPaymentTokenRepository jpa;

    public PaymentTokenRepositoryAdapter(JpaPaymentTokenRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public PaymentToken save(PaymentToken token) {
        var entity = toEntity(token);
        jpa.save(entity);
        return token;
    }

    @Override
    public Optional<PaymentToken> findById(String id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    public List<PaymentToken> findBySessionId(String sessionId) {
        return jpa.findBySessionId(sessionId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void markUsed(String id) {
        jpa.markUsed(id);
    }

    private PaymentTokenEntity toEntity(PaymentToken t) {
        return new PaymentTokenEntity(
                t.getId(), t.getTenantId(), t.getSessionId(), t.getType(),
                t.getCardLastFour(), t.getCardBrand(), t.getCardExpMonth(), t.getCardExpYear(),
                t.getCardFingerprint(), t.getTokenData(), t.getEncryptionKeyId(),
                t.isSingleUse(), t.isUsed(), t.getExpiresAt(), t.getCreatedAt()
        );
    }

    private PaymentToken toDomain(PaymentTokenEntity e) {
        return new PaymentToken(
                e.getId(), e.getTenantId(), e.getSessionId(), e.getType(),
                e.getCardLastFour(), e.getCardBrand(), e.getCardExpMonth(), e.getCardExpYear(),
                e.getCardFingerprint(), e.getTokenData(), e.getEncryptionKeyId(),
                e.isSingleUse(), e.isUsed(), e.getExpiresAt(), e.getCreatedAt()
        );
    }
}
