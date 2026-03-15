package io.nexuspay.dispute.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dispute module configuration.
 *
 * @since 0.2.4 (Sprint 2.4)
 */
@Configuration
public class DisputeConfig {

    @Bean
    @ConfigurationProperties(prefix = "nexuspay.dispute")
    public DisputeProperties disputeProperties() {
        return new DisputeProperties();
    }

    /**
     * Configuration properties for the dispute engine.
     */
    public static class DisputeProperties {

        /**
         * Maximum amount (minor units) eligible for auto-representment.
         * Disputes above this threshold require manual review.
         */
        private long autoSubmitThreshold = 100_000;

        /**
         * Default evidence deadline in days when network does not specify one.
         */
        private int defaultEvidenceDeadlineDays = 20;

        /**
         * Whether auto-representment is enabled.
         */
        private boolean autoRepresentmentEnabled = true;

        public long getAutoSubmitThreshold() { return autoSubmitThreshold; }
        public void setAutoSubmitThreshold(long autoSubmitThreshold) { this.autoSubmitThreshold = autoSubmitThreshold; }
        public int getDefaultEvidenceDeadlineDays() { return defaultEvidenceDeadlineDays; }
        public void setDefaultEvidenceDeadlineDays(int days) { this.defaultEvidenceDeadlineDays = days; }
        public boolean isAutoRepresentmentEnabled() { return autoRepresentmentEnabled; }
        public void setAutoRepresentmentEnabled(boolean enabled) { this.autoRepresentmentEnabled = enabled; }
    }
}
