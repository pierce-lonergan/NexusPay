package io.nexuspay.billing.domain;

import io.nexuspay.common.id.PrefixedId;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A pricing configuration attached to a {@link Product}.
 *
 * <p>Prices define how much to charge, the billing interval, and the
 * pricing model (flat, per-unit, tiered, volume, package).</p>
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
public class Price {

    private String id;
    private String productId;
    private String tenantId;
    private String currency;
    private PricingModel pricingModel;
    private Long unitAmount;
    private List<Map<String, Object>> tiers;
    private String billingInterval;
    private int billingIntervalCount;
    private int trialDays;
    private boolean active;
    private Instant effectiveFrom;
    private Instant createdAt;

    public Price() {
    }

    public static Price createFlat(String productId, String tenantId, String currency,
                                    long unitAmount, String billingInterval, int intervalCount,
                                    int trialDays) {
        Price p = new Price();
        p.id = PrefixedId.price();
        p.productId = productId;
        p.tenantId = tenantId;
        p.currency = currency;
        p.pricingModel = PricingModel.FLAT;
        p.unitAmount = unitAmount;
        p.billingInterval = billingInterval;
        p.billingIntervalCount = intervalCount;
        p.trialDays = trialDays;
        p.active = true;
        p.effectiveFrom = Instant.now();
        p.createdAt = Instant.now();
        return p;
    }

    public static Price createPerUnit(String productId, String tenantId, String currency,
                                       long unitAmount, String billingInterval, int intervalCount,
                                       int trialDays) {
        Price p = createFlat(productId, tenantId, currency, unitAmount, billingInterval, intervalCount, trialDays);
        p.pricingModel = PricingModel.PER_UNIT;
        return p;
    }

    public static Price createTiered(String productId, String tenantId, String currency,
                                      List<Map<String, Object>> tiers, String billingInterval,
                                      int intervalCount, int trialDays) {
        Price p = new Price();
        p.id = PrefixedId.price();
        p.productId = productId;
        p.tenantId = tenantId;
        p.currency = currency;
        p.pricingModel = PricingModel.TIERED;
        p.tiers = tiers;
        p.billingInterval = billingInterval;
        p.billingIntervalCount = intervalCount;
        p.trialDays = trialDays;
        p.active = true;
        p.effectiveFrom = Instant.now();
        p.createdAt = Instant.now();
        return p;
    }

    /**
     * Calculates the charge amount for a given quantity.
     */
    public long calculateAmount(int quantity) {
        return switch (pricingModel) {
            case FLAT -> unitAmount;
            case PER_UNIT -> unitAmount * quantity;
            case TIERED -> calculateTieredAmount(quantity);
            case VOLUME -> calculateVolumeAmount(quantity);
            case PACKAGE -> calculatePackageAmount(quantity);
        };
    }

    private long calculateTieredAmount(int quantity) {
        if (tiers == null || tiers.isEmpty()) return 0;
        long total = 0;
        int remaining = quantity;
        int previousLimit = 0;

        for (Map<String, Object> tier : tiers) {
            int upTo = tier.containsKey("up_to") ? ((Number) tier.get("up_to")).intValue() : Integer.MAX_VALUE;
            long tierUnitAmount = tier.containsKey("unit_amount") ? ((Number) tier.get("unit_amount")).longValue() : 0;
            long flatAmount = tier.containsKey("flat_amount") ? ((Number) tier.get("flat_amount")).longValue() : 0;

            int tierQuantity = Math.min(remaining, upTo - previousLimit);
            if (tierQuantity <= 0) break;

            total += (tierUnitAmount * tierQuantity) + flatAmount;
            remaining -= tierQuantity;
            previousLimit = upTo;

            if (remaining <= 0) break;
        }
        return total;
    }

    private long calculateVolumeAmount(int quantity) {
        if (tiers == null || tiers.isEmpty()) return 0;
        for (Map<String, Object> tier : tiers) {
            int upTo = tier.containsKey("up_to") ? ((Number) tier.get("up_to")).intValue() : Integer.MAX_VALUE;
            if (quantity <= upTo) {
                long tierUnitAmount = ((Number) tier.get("unit_amount")).longValue();
                long flatAmount = tier.containsKey("flat_amount") ? ((Number) tier.get("flat_amount")).longValue() : 0;
                return (tierUnitAmount * quantity) + flatAmount;
            }
        }
        return 0;
    }

    private long calculatePackageAmount(int quantity) {
        if (unitAmount == null || unitAmount == 0) return 0;
        // unitAmount = price per package, tiers[0].up_to = package size
        int packageSize = (tiers != null && !tiers.isEmpty())
                ? ((Number) tiers.getFirst().get("up_to")).intValue()
                : 1;
        int packages = (quantity + packageSize - 1) / packageSize;
        return unitAmount * packages;
    }

    // -- Getters & setters --

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public PricingModel getPricingModel() { return pricingModel; }
    public void setPricingModel(PricingModel pricingModel) { this.pricingModel = pricingModel; }
    public Long getUnitAmount() { return unitAmount; }
    public void setUnitAmount(Long unitAmount) { this.unitAmount = unitAmount; }
    public List<Map<String, Object>> getTiers() { return tiers; }
    public void setTiers(List<Map<String, Object>> tiers) { this.tiers = tiers; }
    public String getBillingInterval() { return billingInterval; }
    public void setBillingInterval(String billingInterval) { this.billingInterval = billingInterval; }
    public int getBillingIntervalCount() { return billingIntervalCount; }
    public void setBillingIntervalCount(int count) { this.billingIntervalCount = count; }
    public int getTrialDays() { return trialDays; }
    public void setTrialDays(int trialDays) { this.trialDays = trialDays; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(Instant effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
