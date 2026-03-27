package io.nexuspay.marketplace.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Marketplace module configuration properties bound to {@code nexuspay.marketplace.*}.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
@Configuration
@ConfigurationProperties(prefix = "nexuspay.marketplace")
public class MarketplaceProperties {

    private PayoutSchedulerConfig payoutScheduler = new PayoutSchedulerConfig();
    private KycConfig kyc = new KycConfig();
    private boolean enabled = true;

    public PayoutSchedulerConfig getPayoutScheduler() { return payoutScheduler; }
    public void setPayoutScheduler(PayoutSchedulerConfig payoutScheduler) { this.payoutScheduler = payoutScheduler; }

    public KycConfig getKyc() { return kyc; }
    public void setKyc(KycConfig kyc) { this.kyc = kyc; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public static class PayoutSchedulerConfig {
        private boolean enabled = false;
        private long intervalMs = 60000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public long getIntervalMs() { return intervalMs; }
        public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }
    }

    public static class KycConfig {
        private String provider = "stub";

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
    }
}
