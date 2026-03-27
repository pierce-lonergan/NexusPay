package io.nexuspay.vault.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for the {@code vaulted_cards} table.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
@Entity
@Table(name = "vaulted_cards")
public class VaultedCardEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "encrypted_pan", nullable = false)
    private byte[] encryptedPan;

    @Column(name = "pan_last4", nullable = false, length = 4)
    private String panLast4;

    @Column(name = "pan_bin", nullable = false, length = 8)
    private String panBin;

    @Column(name = "brand", nullable = false, length = 16)
    private String brand;

    @Column(name = "exp_month", nullable = false)
    private short expMonth;

    @Column(name = "exp_year", nullable = false)
    private short expYear;

    @Column(name = "cardholder_name", length = 256)
    private String cardholderName;

    @Column(name = "encryption_key_id", nullable = false, length = 64)
    private String encryptionKeyId;

    @Column(name = "fingerprint", nullable = false, length = 128)
    private String fingerprint;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

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

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public short getExpMonth() { return expMonth; }
    public void setExpMonth(short expMonth) { this.expMonth = expMonth; }

    public short getExpYear() { return expYear; }
    public void setExpYear(short expYear) { this.expYear = expYear; }

    public String getCardholderName() { return cardholderName; }
    public void setCardholderName(String cardholderName) { this.cardholderName = cardholderName; }

    public String getEncryptionKeyId() { return encryptionKeyId; }
    public void setEncryptionKeyId(String encryptionKeyId) { this.encryptionKeyId = encryptionKeyId; }

    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
