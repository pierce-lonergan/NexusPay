package io.nexuspay.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Configuration properties for the smart routing engine.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
@Component
@ConfigurationProperties(prefix = "nexuspay.routing")
public class RoutingProperties {

    private boolean enabled = true;
    private String defaultStrategy = "SUCCESS_RATE";
    private CascadeProperties cascade = new CascadeProperties();
    private HealthProperties health = new HealthProperties();
    private LatencyProperties latency = new LatencyProperties();
    private AbTestProperties abTesting = new AbTestProperties();

    // Getters and setters
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getDefaultStrategy() { return defaultStrategy; }
    public void setDefaultStrategy(String defaultStrategy) { this.defaultStrategy = defaultStrategy; }
    public CascadeProperties getCascade() { return cascade; }
    public void setCascade(CascadeProperties cascade) { this.cascade = cascade; }
    public HealthProperties getHealth() { return health; }
    public void setHealth(HealthProperties health) { this.health = health; }
    public LatencyProperties getLatency() { return latency; }
    public void setLatency(LatencyProperties latency) { this.latency = latency; }
    public AbTestProperties getAbTesting() { return abTesting; }
    public void setAbTesting(AbTestProperties abTesting) { this.abTesting = abTesting; }

    public static class CascadeProperties {
        private boolean enabled = true;
        private int maxDepth = 3;
        private List<String> softDeclineCodes = List.of("DO_NOT_HONOR", "INSUFFICIENT_FUNDS", "ISSUER_UNAVAILABLE");
        private List<String> hardDeclineCodes = List.of("STOLEN_CARD", "LOST_CARD", "FRAUD", "INVALID_CARD");

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxDepth() { return maxDepth; }
        public void setMaxDepth(int maxDepth) { this.maxDepth = maxDepth; }
        public List<String> getSoftDeclineCodes() { return softDeclineCodes; }
        public void setSoftDeclineCodes(List<String> softDeclineCodes) { this.softDeclineCodes = softDeclineCodes; }
        public List<String> getHardDeclineCodes() { return hardDeclineCodes; }
        public void setHardDeclineCodes(List<String> hardDeclineCodes) { this.hardDeclineCodes = hardDeclineCodes; }
    }

    public static class HealthProperties {
        private int slidingWindowHours = 168; // 7 days
        private int minSampleSize = 100;
        private double unhealthyAuthRateThreshold = 0.70;
        private double unhealthyP95ThresholdMs = 3000;

        public int getSlidingWindowHours() { return slidingWindowHours; }
        public void setSlidingWindowHours(int slidingWindowHours) { this.slidingWindowHours = slidingWindowHours; }
        public int getMinSampleSize() { return minSampleSize; }
        public void setMinSampleSize(int minSampleSize) { this.minSampleSize = minSampleSize; }
        public double getUnhealthyAuthRateThreshold() { return unhealthyAuthRateThreshold; }
        public void setUnhealthyAuthRateThreshold(double unhealthyAuthRateThreshold) { this.unhealthyAuthRateThreshold = unhealthyAuthRateThreshold; }
        public double getUnhealthyP95ThresholdMs() { return unhealthyP95ThresholdMs; }
        public void setUnhealthyP95ThresholdMs(double unhealthyP95ThresholdMs) { this.unhealthyP95ThresholdMs = unhealthyP95ThresholdMs; }
    }

    public static class LatencyProperties {
        private int trackingWindowMinutes = 60;
        private double unhealthyP95ThresholdMs = 3000;

        public int getTrackingWindowMinutes() { return trackingWindowMinutes; }
        public void setTrackingWindowMinutes(int trackingWindowMinutes) { this.trackingWindowMinutes = trackingWindowMinutes; }
        public double getUnhealthyP95ThresholdMs() { return unhealthyP95ThresholdMs; }
        public void setUnhealthyP95ThresholdMs(double unhealthyP95ThresholdMs) { this.unhealthyP95ThresholdMs = unhealthyP95ThresholdMs; }
    }

    public static class AbTestProperties {
        private int minSampleSize = 1000;
        private double confidenceLevel = 0.95;
        private boolean autoPromote = true;

        public int getMinSampleSize() { return minSampleSize; }
        public void setMinSampleSize(int minSampleSize) { this.minSampleSize = minSampleSize; }
        public double getConfidenceLevel() { return confidenceLevel; }
        public void setConfidenceLevel(double confidenceLevel) { this.confidenceLevel = confidenceLevel; }
        public boolean isAutoPromote() { return autoPromote; }
        public void setAutoPromote(boolean autoPromote) { this.autoPromote = autoPromote; }
    }
}
