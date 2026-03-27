package io.nexuspay.marketplace.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Domain model representing a sub-merchant connected account on the platform.
 * Tracks onboarding state, KYC verification, payout preferences, and platform fees.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public class ConnectedAccount {

    private String id;
    private String tenantId;
    private String businessName;
    private String email;
    private AccountState status;
    private KycStatus kycStatus;
    private String country;
    private String defaultCurrency;
    private PayoutSchedule payoutSchedule;
    private long payoutMinimum;
    private BigDecimal platformFeePercent;
    private long platformFeeFixed;
    private String metadata;
    private Instant createdAt;
    private Instant updatedAt;

    public static ConnectedAccount create(String tenantId, String businessName, String email,
                                           String country, String defaultCurrency) {
        ConnectedAccount account = new ConnectedAccount();
        account.id = "ca_" + UUID.randomUUID().toString().replace("-", "");
        account.tenantId = tenantId;
        account.businessName = businessName;
        account.email = email;
        account.status = AccountState.ONBOARDING;
        account.kycStatus = KycStatus.PENDING;
        account.country = country;
        account.defaultCurrency = defaultCurrency;
        account.payoutSchedule = PayoutSchedule.DAILY;
        account.payoutMinimum = 0;
        account.platformFeePercent = BigDecimal.ZERO;
        account.platformFeeFixed = 0;
        account.createdAt = Instant.now();
        account.updatedAt = Instant.now();
        return account;
    }

    public void activate() {
        if (this.kycStatus != KycStatus.VERIFIED) {
            throw new IllegalStateException("Cannot activate account without verified KYC");
        }
        this.status = AccountState.ACTIVE;
        this.updatedAt = Instant.now();
    }

    public void suspend(String reason) {
        this.status = AccountState.SUSPENDED;
        this.updatedAt = Instant.now();
    }

    public void close() {
        this.status = AccountState.CLOSED;
        this.updatedAt = Instant.now();
    }

    public void updateKycStatus(KycStatus newStatus) {
        this.kycStatus = newStatus;
        if (newStatus == KycStatus.VERIFIED && this.status == AccountState.ONBOARDING) {
            this.status = AccountState.VERIFIED;
        }
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getBusinessName() { return businessName; }
    public void setBusinessName(String businessName) { this.businessName = businessName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public AccountState getStatus() { return status; }
    public void setStatus(AccountState status) { this.status = status; }

    public KycStatus getKycStatus() { return kycStatus; }
    public void setKycStatus(KycStatus kycStatus) { this.kycStatus = kycStatus; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getDefaultCurrency() { return defaultCurrency; }
    public void setDefaultCurrency(String defaultCurrency) { this.defaultCurrency = defaultCurrency; }

    public PayoutSchedule getPayoutSchedule() { return payoutSchedule; }
    public void setPayoutSchedule(PayoutSchedule payoutSchedule) { this.payoutSchedule = payoutSchedule; }

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
