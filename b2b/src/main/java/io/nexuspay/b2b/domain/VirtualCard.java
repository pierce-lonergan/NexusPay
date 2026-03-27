package io.nexuspay.b2b.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Domain model representing a virtual card issued for B2B payments.
 * Supports single-use and multi-use cards with spend controls and MCC restrictions.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
public class VirtualCard {

    private String id;
    private String tenantId;
    private String issuingProvider;
    private String externalCardId;
    private String cardLast4;
    private VirtualCardType cardType;
    private long amountLimit;
    private String currency;
    private List<String> merchantCategoryCodes;
    private Instant expiresAt;
    private VirtualCardStatus status;
    private long spentAmount;
    private String purchaseOrderId;
    private Instant createdAt;

    public static VirtualCard create(String tenantId, String issuingProvider,
                                      VirtualCardType cardType, long amountLimit,
                                      String currency, Instant expiresAt) {
        VirtualCard card = new VirtualCard();
        card.id = "vc_" + UUID.randomUUID().toString().replace("-", "");
        card.tenantId = tenantId;
        card.issuingProvider = issuingProvider;
        card.cardType = cardType;
        card.amountLimit = amountLimit;
        card.currency = currency;
        card.expiresAt = expiresAt;
        card.status = VirtualCardStatus.ACTIVE;
        card.spentAmount = 0;
        card.createdAt = Instant.now();
        return card;
    }

    public void recordSpend(long amount) {
        this.spentAmount += amount;
        if (this.cardType == VirtualCardType.SINGLE_USE) {
            this.status = VirtualCardStatus.CANCELLED;
        }
    }

    public boolean hasAvailableBalance(long amount) {
        return (this.amountLimit - this.spentAmount) >= amount;
    }

    public long availableBalance() {
        return this.amountLimit - this.spentAmount;
    }

    public void freeze() {
        this.status = VirtualCardStatus.FROZEN;
    }

    public void unfreeze() {
        this.status = VirtualCardStatus.ACTIVE;
    }

    public void cancel() {
        this.status = VirtualCardStatus.CANCELLED;
    }

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

    public VirtualCardType getCardType() { return cardType; }
    public void setCardType(VirtualCardType cardType) { this.cardType = cardType; }

    public long getAmountLimit() { return amountLimit; }
    public void setAmountLimit(long amountLimit) { this.amountLimit = amountLimit; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public List<String> getMerchantCategoryCodes() { return merchantCategoryCodes; }
    public void setMerchantCategoryCodes(List<String> merchantCategoryCodes) { this.merchantCategoryCodes = merchantCategoryCodes; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public VirtualCardStatus getStatus() { return status; }
    public void setStatus(VirtualCardStatus status) { this.status = status; }

    public long getSpentAmount() { return spentAmount; }
    public void setSpentAmount(long spentAmount) { this.spentAmount = spentAmount; }

    public String getPurchaseOrderId() { return purchaseOrderId; }
    public void setPurchaseOrderId(String purchaseOrderId) { this.purchaseOrderId = purchaseOrderId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
