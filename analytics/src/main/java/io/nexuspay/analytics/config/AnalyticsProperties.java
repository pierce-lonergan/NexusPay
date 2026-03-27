package io.nexuspay.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Analytics module configuration properties bound to {@code nexuspay.analytics.*}.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
@Configuration
@ConfigurationProperties(prefix = "nexuspay.analytics")
public class AnalyticsProperties {

    private boolean enabled = true;
    private Pipeline pipeline = new Pipeline();
    private Rollup rollup = new Rollup();
    private PspHealth pspHealth = new PspHealth();
    private Cache cache = new Cache();
    private Query query = new Query();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Pipeline getPipeline() { return pipeline; }
    public void setPipeline(Pipeline pipeline) { this.pipeline = pipeline; }

    public Rollup getRollup() { return rollup; }
    public void setRollup(Rollup rollup) { this.rollup = rollup; }

    public PspHealth getPspHealth() { return pspHealth; }
    public void setPspHealth(PspHealth pspHealth) { this.pspHealth = pspHealth; }

    public Cache getCache() { return cache; }
    public void setCache(Cache cache) { this.cache = cache; }

    public Query getQuery() { return query; }
    public void setQuery(Query query) { this.query = query; }

    public static class Pipeline {
        private String consumerGroup = "nexuspay-analytics";
        private int batchSize = 100;
        private int batchTimeoutMs = 2000;

        public String getConsumerGroup() { return consumerGroup; }
        public void setConsumerGroup(String cg) { this.consumerGroup = cg; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int bs) { this.batchSize = bs; }
        public int getBatchTimeoutMs() { return batchTimeoutMs; }
        public void setBatchTimeoutMs(int ms) { this.batchTimeoutMs = ms; }
    }

    public static class Rollup {
        private int hourlyRetentionDays = 90;
        private int dailyRetentionDays = 730;
        private int monthlyRetentionDays = 3650;
        private String dailyRollupCron = "0 5 0 * * *";
        private String monthlyRollupCron = "0 10 0 1 * *";
        private String materializedViewRefreshCron = "0 0 * * * *";

        public int getHourlyRetentionDays() { return hourlyRetentionDays; }
        public void setHourlyRetentionDays(int d) { this.hourlyRetentionDays = d; }
        public int getDailyRetentionDays() { return dailyRetentionDays; }
        public void setDailyRetentionDays(int d) { this.dailyRetentionDays = d; }
        public int getMonthlyRetentionDays() { return monthlyRetentionDays; }
        public void setMonthlyRetentionDays(int d) { this.monthlyRetentionDays = d; }
        public String getDailyRollupCron() { return dailyRollupCron; }
        public void setDailyRollupCron(String c) { this.dailyRollupCron = c; }
        public String getMonthlyRollupCron() { return monthlyRollupCron; }
        public void setMonthlyRollupCron(String c) { this.monthlyRollupCron = c; }
        public String getMaterializedViewRefreshCron() { return materializedViewRefreshCron; }
        public void setMaterializedViewRefreshCron(String c) { this.materializedViewRefreshCron = c; }
    }

    public static class PspHealth {
        private long snapshotIntervalMs = 300000;
        private double anomalyStdDevThreshold = 2.0;
        private Weights scoringWeights = new Weights();

        public long getSnapshotIntervalMs() { return snapshotIntervalMs; }
        public void setSnapshotIntervalMs(long ms) { this.snapshotIntervalMs = ms; }
        public double getAnomalyStdDevThreshold() { return anomalyStdDevThreshold; }
        public void setAnomalyStdDevThreshold(double t) { this.anomalyStdDevThreshold = t; }
        public Weights getScoringWeights() { return scoringWeights; }
        public void setScoringWeights(Weights w) { this.scoringWeights = w; }

        public static class Weights {
            private double authRate = 0.50;
            private double latency = 0.30;
            private double errorRate = 0.20;

            public double getAuthRate() { return authRate; }
            public void setAuthRate(double a) { this.authRate = a; }
            public double getLatency() { return latency; }
            public void setLatency(double l) { this.latency = l; }
            public double getErrorRate() { return errorRate; }
            public void setErrorRate(double e) { this.errorRate = e; }
        }
    }

    public static class Cache {
        private Duration ttl = Duration.ofMinutes(5);

        public Duration getTtl() { return ttl; }
        public void setTtl(Duration ttl) { this.ttl = ttl; }
    }

    public static class Query {
        private int maxDateRangeDays = 365;
        private String defaultGranularity = "DAILY";

        public int getMaxDateRangeDays() { return maxDateRangeDays; }
        public void setMaxDateRangeDays(int d) { this.maxDateRangeDays = d; }
        public String getDefaultGranularity() { return defaultGranularity; }
        public void setDefaultGranularity(String g) { this.defaultGranularity = g; }
    }
}
