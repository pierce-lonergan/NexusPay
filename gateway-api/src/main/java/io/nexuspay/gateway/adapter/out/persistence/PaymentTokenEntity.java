package io.nexuspay.gateway.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for the {@code payment_tokens} table.
 *
 * @since 0.3.5 (Sprint 3.5)
 */
@Entity
@Table(name = "payment_tokens")
public class PaymentTokenEntity {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(nullable = false, length = 20)
    private String type;

    @Column(name = "card_last_four", length = 4)
    private String cardLastFour;

    @Column(name = "card_brand", length = 20)
    private String cardBrand;

    @Column(name = "card_exp_month")
    private Integer cardExpMonth;

    @Column(name = "card_exp_year")
    private Integer cardExpYear;

    @Column(name = "card_fingerprint", length = 64)
    private String cardFingerprint;

    @Column(name = "token_data")
    private byte[] tokenData;

    @Column(name = "encryption_key_id")
    private String encryptionKeyId;

    @Column(name = "single_use", nullable = false)
    private boolean singleUse;

    @Column(nullable = false)
    private boolean used;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentTokenEntity() {
    }

    public PaymentTokenEntity(String id, String tenantId, String sessionId, String type,
                               String cardLastFour, String cardBrand, Integer cardExpMonth,
                               Integer cardExpYear, String cardFingerprint, byte[] tokenData,
                               String encryptionKeyId, boolean singleUse, boolean used,
                               Instant expiresAt, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.sessionId = sessionId;
        this.type = type;
        this.cardLastFour = cardLastFour;
        this.cardBrand = cardBrand;
        this.cardExpMonth = cardExpMonth;
        this.cardExpYear = cardExpYear;
        this.cardFingerprint = cardFingerprint;
        this.tokenData = tokenData;
        this.encryptionKeyId = encryptionKeyId;
        this.singleUse = singleUse;
        this.used = used;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getSessionId() { return sessionId; }
    public String getType() { return type; }
    public String getCardLastFour() { return cardLastFour; }
    public String getCardBrand() { return cardBrand; }
    public Integer getCardExpMonth() { return cardExpMonth; }
    public Integer getCardExpYear() { return cardExpYear; }
    public String getCardFingerprint() { return cardFingerprint; }
    public byte[] getTokenData() { return tokenData; }
    public String getEncryptionKeyId() { return encryptionKeyId; }
    public boolean isSingleUse() { return singleUse; }
    public boolean isUsed() { return used; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void setUsed(boolean used) {
        this.used = used;
    }
}
