package io.nexuspay.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Configuration properties for FX rate management and cross-border compliance.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
@Component
@ConfigurationProperties(prefix = "nexuspay.fx")
public class FxProperties {

    private boolean enabled = true;
    private String defaultProvider = "ecb";
    private CacheProperties cache = new CacheProperties();
    private RateLockProperties rateLock = new RateLockProperties();
    private EcbProperties ecb = new EcbProperties();
    private OpenExchangeRatesProperties openExchangeRates = new OpenExchangeRatesProperties();
    private ComplianceProperties compliance = new ComplianceProperties();

    // Getters and setters
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getDefaultProvider() { return defaultProvider; }
    public void setDefaultProvider(String defaultProvider) { this.defaultProvider = defaultProvider; }
    public CacheProperties getCache() { return cache; }
    public void setCache(CacheProperties cache) { this.cache = cache; }
    public RateLockProperties getRateLock() { return rateLock; }
    public void setRateLock(RateLockProperties rateLock) { this.rateLock = rateLock; }
    public EcbProperties getEcb() { return ecb; }
    public void setEcb(EcbProperties ecb) { this.ecb = ecb; }
    public OpenExchangeRatesProperties getOpenExchangeRates() { return openExchangeRates; }
    public void setOpenExchangeRates(OpenExchangeRatesProperties openExchangeRates) { this.openExchangeRates = openExchangeRates; }
    public ComplianceProperties getCompliance() { return compliance; }
    public void setCompliance(ComplianceProperties compliance) { this.compliance = compliance; }

    public static class CacheProperties {
        private Duration ttl = Duration.ofHours(1);
        private Duration staleTtl = Duration.ofHours(24);
        private Duration refreshInterval = Duration.ofMinutes(30);

        public Duration getTtl() { return ttl; }
        public void setTtl(Duration ttl) { this.ttl = ttl; }
        public Duration getStaleTtl() { return staleTtl; }
        public void setStaleTtl(Duration staleTtl) { this.staleTtl = staleTtl; }
        public Duration getRefreshInterval() { return refreshInterval; }
        public void setRefreshInterval(Duration refreshInterval) { this.refreshInterval = refreshInterval; }
    }

    public static class RateLockProperties {
        private Duration defaultDuration = Duration.ofMinutes(15);
        private Duration maxDuration = Duration.ofHours(1);

        public Duration getDefaultDuration() { return defaultDuration; }
        public void setDefaultDuration(Duration defaultDuration) { this.defaultDuration = defaultDuration; }
        public Duration getMaxDuration() { return maxDuration; }
        public void setMaxDuration(Duration maxDuration) { this.maxDuration = maxDuration; }
    }

    public static class EcbProperties {
        private String apiUrl = "https://data.ecb.europa.eu/service/data/EXR";
        private String updateSchedule = "0 16 * * 1-5";

        public String getApiUrl() { return apiUrl; }
        public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
        public String getUpdateSchedule() { return updateSchedule; }
        public void setUpdateSchedule(String updateSchedule) { this.updateSchedule = updateSchedule; }
    }

    public static class OpenExchangeRatesProperties {
        private String apiUrl = "https://openexchangerates.org/api";
        private String appId;

        public String getApiUrl() { return apiUrl; }
        public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
        public String getAppId() { return appId; }
        public void setAppId(String appId) { this.appId = appId; }
    }

    public static class ComplianceProperties {
        private List<String> sanctionedCountries = List.of("KP", "IR", "SY", "CU");
        private int crossBorderAmountReportingThreshold = 10000;

        public List<String> getSanctionedCountries() { return sanctionedCountries; }
        public void setSanctionedCountries(List<String> sanctionedCountries) { this.sanctionedCountries = sanctionedCountries; }
        public int getCrossBorderAmountReportingThreshold() { return crossBorderAmountReportingThreshold; }
        public void setCrossBorderAmountReportingThreshold(int crossBorderAmountReportingThreshold) { this.crossBorderAmountReportingThreshold = crossBorderAmountReportingThreshold; }
    }
}
