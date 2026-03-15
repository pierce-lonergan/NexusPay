package io.nexuspay.billing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Billing module configuration.
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
@Configuration
public class BillingConfig {

    @Bean
    @ConfigurationProperties(prefix = "nexuspay.billing")
    public BillingProperties billingProperties() {
        return new BillingProperties();
    }

    /**
     * Configuration properties for the billing engine.
     */
    public static class BillingProperties {

        private DunningProperties dunning = new DunningProperties();

        public DunningProperties getDunning() { return dunning; }
        public void setDunning(DunningProperties dunning) { this.dunning = dunning; }

        public static class DunningProperties {

            /** Days after initial failure for each retry attempt. */
            private int[] retrySchedule = {1, 3, 5, 7};

            /** Grace period days after last retry before cancellation. */
            private int gracePeriodDays = 3;

            /** Whether smart retry optimization is enabled. */
            private boolean smartRetryEnabled = true;

            /** Optimal hour of day (local time) for smart retries. */
            private int optimalHour = 10;

            public int[] getRetrySchedule() { return retrySchedule; }
            public void setRetrySchedule(int[] retrySchedule) { this.retrySchedule = retrySchedule; }
            public int getGracePeriodDays() { return gracePeriodDays; }
            public void setGracePeriodDays(int gracePeriodDays) { this.gracePeriodDays = gracePeriodDays; }
            public boolean isSmartRetryEnabled() { return smartRetryEnabled; }
            public void setSmartRetryEnabled(boolean enabled) { this.smartRetryEnabled = enabled; }
            public int getOptimalHour() { return optimalHour; }
            public void setOptimalHour(int optimalHour) { this.optimalHour = optimalHour; }
        }
    }
}
