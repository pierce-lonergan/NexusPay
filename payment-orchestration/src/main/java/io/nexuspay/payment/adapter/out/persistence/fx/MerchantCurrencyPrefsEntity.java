package io.nexuspay.payment.adapter.out.persistence.fx;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for merchant currency preferences.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
@Entity
@Table(name = "merchant_currency_prefs",
        uniqueConstraints = @UniqueConstraint(columnNames = "tenant_id"))
public class MerchantCurrencyPrefsEntity {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64, unique = true)
    private String tenantId;

    @Column(name = "settlement_currency", nullable = false, length = 3)
    private String settlementCurrency;

    @Column(name = "auto_convert", nullable = false)
    private boolean autoConvert = true;

    @Column(name = "fx_markup_bps", nullable = false)
    private int fxMarkupBps = 0;

    @Column(name = "rate_provider", nullable = false, length = 50)
    private String rateProvider = "ECB";

    @Column(name = "rate_lock_duration_minutes", nullable = false)
    private int rateLockDurationMinutes = 15;

    /**
     * Server-authoritative ISO-2 destination country for the OFAC screen (B-025). Nullable:
     * null = unknown → the sanctions gate fails closed to REVIEW on cross-border-capable flows.
     */
    @Column(name = "merchant_country", length = 2)
    private String merchantCountry;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MerchantCurrencyPrefsEntity() {}

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getSettlementCurrency() { return settlementCurrency; }
    public void setSettlementCurrency(String settlementCurrency) { this.settlementCurrency = settlementCurrency; }
    public boolean isAutoConvert() { return autoConvert; }
    public void setAutoConvert(boolean autoConvert) { this.autoConvert = autoConvert; }
    public int getFxMarkupBps() { return fxMarkupBps; }
    public void setFxMarkupBps(int fxMarkupBps) { this.fxMarkupBps = fxMarkupBps; }
    public String getRateProvider() { return rateProvider; }
    public void setRateProvider(String rateProvider) { this.rateProvider = rateProvider; }
    public int getRateLockDurationMinutes() { return rateLockDurationMinutes; }
    public void setRateLockDurationMinutes(int rateLockDurationMinutes) { this.rateLockDurationMinutes = rateLockDurationMinutes; }
    public String getMerchantCountry() { return merchantCountry; }
    public void setMerchantCountry(String merchantCountry) { this.merchantCountry = merchantCountry; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
