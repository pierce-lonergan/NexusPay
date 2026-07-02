package io.nexuspay.b2b.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * B2B module configuration properties bound to {@code nexuspay.b2b.*}.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
@Configuration
@ConfigurationProperties(prefix = "nexuspay.b2b")
public class B2bProperties {

    private boolean enabled = true;
    private CardIssuingConfig cardIssuing = new CardIssuingConfig();
    private VendorPaymentConfig vendorPayment = new VendorPaymentConfig();
    private Level23Config level23 = new Level23Config();

    /**
     * GAP-068 maker-checker threshold in MINOR units, bound to
     * {@code nexuspay.b2b.approval-threshold} (relaxed binding). A vendor-payment approval or a
     * purchase-order approval whose (total) amount is {@code >=} this threshold requires a SECOND
     * principal: the approve call creates a PENDING approval (202) and a DIFFERENT admin must
     * review it via {@code POST /v1/b2b/approvals/{id}/approve} (requester != reviewer AND
     * creator != approver, both fail-closed). Below the threshold the single-step approve stands.
     * Default 50000 mirrors {@code nexuspay.iam.refund-approval-threshold}.
     */
    private long approvalThreshold = 50_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getApprovalThreshold() { return approvalThreshold; }
    public void setApprovalThreshold(long approvalThreshold) { this.approvalThreshold = approvalThreshold; }

    public CardIssuingConfig getCardIssuing() { return cardIssuing; }
    public void setCardIssuing(CardIssuingConfig cardIssuing) { this.cardIssuing = cardIssuing; }

    public VendorPaymentConfig getVendorPayment() { return vendorPayment; }
    public void setVendorPayment(VendorPaymentConfig vendorPayment) { this.vendorPayment = vendorPayment; }

    public Level23Config getLevel23() { return level23; }
    public void setLevel23(Level23Config level23) { this.level23 = level23; }

    public static class CardIssuingConfig {
        private String provider = "stub";
        private long defaultExpiryDays = 90;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }

        public long getDefaultExpiryDays() { return defaultExpiryDays; }
        public void setDefaultExpiryDays(long defaultExpiryDays) { this.defaultExpiryDays = defaultExpiryDays; }
    }

    public static class VendorPaymentConfig {
        private String executionProvider = "stub";
        private int batchMaxSize = 100;

        public String getExecutionProvider() { return executionProvider; }
        public void setExecutionProvider(String executionProvider) { this.executionProvider = executionProvider; }

        public int getBatchMaxSize() { return batchMaxSize; }
        public void setBatchMaxSize(int batchMaxSize) { this.batchMaxSize = batchMaxSize; }
    }

    public static class Level23Config {
        private boolean enrichmentEnabled = true;
        private long level3Threshold = 500000; // $5,000 in minor units

        public boolean isEnrichmentEnabled() { return enrichmentEnabled; }
        public void setEnrichmentEnabled(boolean enrichmentEnabled) { this.enrichmentEnabled = enrichmentEnabled; }

        public long getLevel3Threshold() { return level3Threshold; }
        public void setLevel3Threshold(long level3Threshold) { this.level3Threshold = level3Threshold; }
    }
}
