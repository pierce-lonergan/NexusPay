package io.nexuspay.vault.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain model representing a PAN encrypted and stored in the vault.
 * The encrypted PAN is never exposed via any external API.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public class VaultedCard {

    private String id;
    private String tenantId;
    private byte[] encryptedPan;
    private String panLast4;
    private String panBin;
    private CardBrand brand;
    private int expMonth;
    private int expYear;
    private String cardholderName;
    private String encryptionKeyId;
    private String fingerprint;
    private Instant createdAt;

    public static VaultedCard create(String tenantId, byte[] encryptedPan, String panLast4,
                                     String panBin, CardBrand brand, int expMonth, int expYear,
                                     String cardholderName, String encryptionKeyId, String fingerprint) {
        VaultedCard card = new VaultedCard();
        card.id = "vc_" + UUID.randomUUID().toString().replace("-", "");
        card.tenantId = tenantId;
        card.encryptedPan = encryptedPan;
        card.panLast4 = panLast4;
        card.panBin = panBin;
        card.brand = brand;
        card.expMonth = expMonth;
        card.expYear = expYear;
        card.cardholderName = cardholderName;
        card.encryptionKeyId = encryptionKeyId;
        card.fingerprint = fingerprint;
        card.createdAt = Instant.now();
        return card;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public byte[] getEncryptedPan() { return encryptedPan; }
    public void setEncryptedPan(byte[] encryptedPan) { this.encryptedPan = encryptedPan; }

    public String getPanLast4() { return panLast4; }
    public void setPanLast4(String panLast4) { this.panLast4 = panLast4; }

    public String getPanBin() { return panBin; }
    public void setPanBin(String panBin) { this.panBin = panBin; }

    public CardBrand getBrand() { return brand; }
    public void setBrand(CardBrand brand) { this.brand = brand; }

    public int getExpMonth() { return expMonth; }
    public void setExpMonth(int expMonth) { this.expMonth = expMonth; }

    public int getExpYear() { return expYear; }
    public void setExpYear(int expYear) { this.expYear = expYear; }

    public String getCardholderName() { return cardholderName; }
    public void setCardholderName(String cardholderName) { this.cardholderName = cardholderName; }

    public String getEncryptionKeyId() { return encryptionKeyId; }
    public void setEncryptionKeyId(String encryptionKeyId) { this.encryptionKeyId = encryptionKeyId; }

    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
