package io.nexuspay.reconciliation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Reconciliation module configuration.
 *
 * @since 0.2.0 (Sprint 2.3)
 */
@Configuration
public class ReconciliationConfig {

    @Bean
    @ConfigurationProperties(prefix = "nexuspay.reconciliation")
    public ReconciliationProperties reconciliationProperties() {
        return new ReconciliationProperties();
    }

    /**
     * Configuration properties for the reconciliation engine.
     */
    public static class ReconciliationProperties {

        /**
         * Amount tolerance in minor units for fuzzy matching (default: 1 = $0.01).
         */
        private long amountTolerance = 1;

        /**
         * Date range tolerance in days for settlement date matching.
         */
        private int dateRangeDays = 7;

        /**
         * Maximum settlement records to process per batch.
         */
        private int maxBatchSize = 10000;

        public long getAmountTolerance() { return amountTolerance; }
        public void setAmountTolerance(long amountTolerance) { this.amountTolerance = amountTolerance; }
        public int getDateRangeDays() { return dateRangeDays; }
        public void setDateRangeDays(int dateRangeDays) { this.dateRangeDays = dateRangeDays; }
        public int getMaxBatchSize() { return maxBatchSize; }
        public void setMaxBatchSize(int maxBatchSize) { this.maxBatchSize = maxBatchSize; }
    }
}
