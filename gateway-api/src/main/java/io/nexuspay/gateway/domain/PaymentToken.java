package io.nexuspay.gateway.domain;

import java.time.Instant;

/**
 * A tokenized payment method. The SDK submits card data (or wallet tokens)
 * which is encrypted and stored as a token. The token ID is then used to
 * confirm the payment without the merchant server ever seeing raw card data.
 *
 * <p>Single-use tokens expire after 15 minutes. Multi-use tokens (for
 * returning customers) expire after 365 days and are deduplicated by
 * card fingerprint.
 *
 * @since 0.3.5 (Sprint 3.5)
 */
public class PaymentToken {

    public static final String TYPE_CARD = "card";
    public static final String TYPE_APPLE_PAY = "apple_pay";
    public static final String TYPE_GOOGLE_PAY = "google_pay";
    public static final String TYPE_BANK_REDIRECT = "bank_redirect";
    public static final String TYPE_BNPL = "bnpl";

    private final String id;
    private final String tenantId;
    private final String sessionId;
    private final String type;
    private final String cardLastFour;
    private final String cardBrand;
    private final Integer cardExpMonth;
    private final Integer cardExpYear;
    private final String cardFingerprint;
    private final byte[] tokenData;
    private final String encryptionKeyId;
    private final boolean singleUse;
    private boolean used;
    private final Instant expiresAt;
    private final Instant createdAt;

    public PaymentToken(String id, String tenantId, String sessionId, String type,
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

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isUsable() {
        return !used && !isExpired();
    }

    public void markUsed() {
        this.used = true;
    }

    // Getters

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
}
