package io.nexuspay.fraud.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Fraud module configuration properties bound to {@code nexuspay.fraud.*}.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
@Configuration
@ConfigurationProperties(prefix = "nexuspay.fraud")
public class FraudProperties {

    private boolean enabled = true;
    private NativeRules nativeRules = new NativeRules();
    private Scoring scoring = new Scoring();
    private Frm frm = new Frm();
    private DeviceFingerprint deviceFingerprint = new DeviceFingerprint();

    // --- Getters & Setters ---

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public NativeRules getNativeRules() { return nativeRules; }
    public void setNativeRules(NativeRules nativeRules) { this.nativeRules = nativeRules; }

    public Scoring getScoring() { return scoring; }
    public void setScoring(Scoring scoring) { this.scoring = scoring; }

    public Frm getFrm() { return frm; }
    public void setFrm(Frm frm) { this.frm = frm; }

    public DeviceFingerprint getDeviceFingerprint() { return deviceFingerprint; }
    public void setDeviceFingerprint(DeviceFingerprint deviceFingerprint) { this.deviceFingerprint = deviceFingerprint; }

    public static class NativeRules {
        private Duration cacheTtl = Duration.ofMinutes(5);
        private int defaultBlockThreshold = 80;
        private int defaultReviewThreshold = 50;

        public Duration getCacheTtl() { return cacheTtl; }
        public void setCacheTtl(Duration cacheTtl) { this.cacheTtl = cacheTtl; }

        public int getDefaultBlockThreshold() { return defaultBlockThreshold; }
        public void setDefaultBlockThreshold(int t) { this.defaultBlockThreshold = t; }

        public int getDefaultReviewThreshold() { return defaultReviewThreshold; }
        public void setDefaultReviewThreshold(int t) { this.defaultReviewThreshold = t; }
    }

    public static class Scoring {
        private double nativeWeight = 0.6;
        private double frmWeight = 0.4;

        public double getNativeWeight() { return nativeWeight; }
        public void setNativeWeight(double nativeWeight) { this.nativeWeight = nativeWeight; }

        public double getFrmWeight() { return frmWeight; }
        public void setFrmWeight(double frmWeight) { this.frmWeight = frmWeight; }
    }

    public static class Frm {
        private String primaryProvider = "sift";
        private String fallbackProvider = "signifyd";
        private Duration timeout = Duration.ofSeconds(2);
        private CircuitBreaker circuitBreaker = new CircuitBreaker();
        private SiftConfig sift = new SiftConfig();
        private SignifydConfig signifyd = new SignifydConfig();

        public String getPrimaryProvider() { return primaryProvider; }
        public void setPrimaryProvider(String p) { this.primaryProvider = p; }

        public String getFallbackProvider() { return fallbackProvider; }
        public void setFallbackProvider(String f) { this.fallbackProvider = f; }

        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }

        public CircuitBreaker getCircuitBreaker() { return circuitBreaker; }
        public void setCircuitBreaker(CircuitBreaker cb) { this.circuitBreaker = cb; }

        public SiftConfig getSift() { return sift; }
        public void setSift(SiftConfig sift) { this.sift = sift; }

        public SignifydConfig getSignifyd() { return signifyd; }
        public void setSignifyd(SignifydConfig signifyd) { this.signifyd = signifyd; }

        public static class CircuitBreaker {
            private int failureRateThreshold = 50;
            private Duration waitDurationInOpenState = Duration.ofSeconds(30);
            private int slidingWindowSize = 20;

            public int getFailureRateThreshold() { return failureRateThreshold; }
            public void setFailureRateThreshold(int t) { this.failureRateThreshold = t; }

            public Duration getWaitDurationInOpenState() { return waitDurationInOpenState; }
            public void setWaitDurationInOpenState(Duration d) { this.waitDurationInOpenState = d; }

            public int getSlidingWindowSize() { return slidingWindowSize; }
            public void setSlidingWindowSize(int s) { this.slidingWindowSize = s; }
        }

        public static class SiftConfig {
            private String apiUrl = "https://api.sift.com/v205";
            private String apiKey = "";
            private String accountId = "";

            public String getApiUrl() { return apiUrl; }
            public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
            public String getApiKey() { return apiKey; }
            public void setApiKey(String apiKey) { this.apiKey = apiKey; }
            public String getAccountId() { return accountId; }
            public void setAccountId(String accountId) { this.accountId = accountId; }
        }

        public static class SignifydConfig {
            private String apiUrl = "https://api.signifyd.com/v2";
            private String apiKey = "";
            private String teamId = "";

            public String getApiUrl() { return apiUrl; }
            public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
            public String getApiKey() { return apiKey; }
            public void setApiKey(String apiKey) { this.apiKey = apiKey; }
            public String getTeamId() { return teamId; }
            public void setTeamId(String teamId) { this.teamId = teamId; }
        }
    }

    public static class DeviceFingerprint {
        private boolean enabled = true;
        private int reputationDecayDays = 90;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getReputationDecayDays() { return reputationDecayDays; }
        public void setReputationDecayDays(int days) { this.reputationDecayDays = days; }
    }
}
