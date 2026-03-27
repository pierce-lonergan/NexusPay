package io.nexuspay.b2b.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for the {@code virtual_cards} table.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
@Entity
@Table(name = "virtual_cards")
public class VirtualCardEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "issuing_provider", nullable = false, length = 32)
    private String issuingProvider;

    @Column(name = "external_card_id", length = 128)
    private String externalCardId;

    @Column(name = "card_last4", length = 4)
    private String cardLast4;

    @Column(name = "card_type", nullable = false, length = 16)
    private String cardType;

    @Column(name = "amount_limit", nullable = false)
    private long amountLimit;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "merchant_category_codes", columnDefinition = "text[]")
    private String merchantCategoryCodes;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "spent_amount", nullable = false)
    private long spentAmount;

    @Column(name = "purchase_order_id", length = 64)
    private String purchaseOrderId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getIssuingProvider() { return issuingProvider; }
    public void setIssuingProvider(String issuingProvider) { this.issuingProvider = issuingProvider; }
    public String getExternalCardId() { return externalCardId; }
    public void setExternalCardId(String externalCardId) { this.externalCardId = externalCardId; }
    public String getCardLast4() { return cardLast4; }
    public void setCardLast4(String cardLast4) { this.cardLast4 = cardLast4; }
    public String getCardType() { return cardType; }
    public void setCardType(String cardType) { this.cardType = cardType; }
    public long getAmountLimit() { return amountLimit; }
    public void setAmountLimit(long amountLimit) { this.amountLimit = amountLimit; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getMerchantCategoryCodes() { return merchantCategoryCodes; }
    public void setMerchantCategoryCodes(String merchantCategoryCodes) { this.merchantCategoryCodes = merchantCategoryCodes; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getSpentAmount() { return spentAmount; }
    public void setSpentAmount(long spentAmount) { this.spentAmount = spentAmount; }
    public String getPurchaseOrderId() { return purchaseOrderId; }
    public void setPurchaseOrderId(String purchaseOrderId) { this.purchaseOrderId = purchaseOrderId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
