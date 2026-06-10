package io.nexuspay.marketplace.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity for the {@code connected_accounts} table.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
@Entity
@Table(name = "connected_accounts")
public class ConnectedAccountEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "business_name", nullable = false, length = 256)
    private String businessName;

    @Column(name = "email", nullable = false, length = 256)
    private String email;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "kyc_status", nullable = false, length = 16)
    private String kycStatus;

    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @Column(name = "default_currency", nullable = false, length = 3)
    private String defaultCurrency;

    @Column(name = "payout_schedule", nullable = false, length = 16)
    private String payoutSchedule;

    @Column(name = "payout_minimum")
    private long payoutMinimum;

    @Column(name = "platform_fee_percent", precision = 5, scale = 2)
    private BigDecimal platformFeePercent;

    @Column(name = "platform_fee_fixed")
    private long platformFeeFixed;

    @Column(name = "metadata", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String metadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getBusinessName() { return businessName; }
    public void setBusinessName(String businessName) { this.businessName = businessName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getKycStatus() { return kycStatus; }
    public void setKycStatus(String kycStatus) { this.kycStatus = kycStatus; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getDefaultCurrency() { return defaultCurrency; }
    public void setDefaultCurrency(String defaultCurrency) { this.defaultCurrency = defaultCurrency; }

    public String getPayoutSchedule() { return payoutSchedule; }
    public void setPayoutSchedule(String payoutSchedule) { this.payoutSchedule = payoutSchedule; }

    public long getPayoutMinimum() { return payoutMinimum; }
    public void setPayoutMinimum(long payoutMinimum) { this.payoutMinimum = payoutMinimum; }

    public BigDecimal getPlatformFeePercent() { return platformFeePercent; }
    public void setPlatformFeePercent(BigDecimal platformFeePercent) { this.platformFeePercent = platformFeePercent; }

    public long getPlatformFeeFixed() { return platformFeeFixed; }
    public void setPlatformFeeFixed(long platformFeeFixed) { this.platformFeeFixed = platformFeeFixed; }

    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
