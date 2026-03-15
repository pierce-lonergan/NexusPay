package io.nexuspay.fraud.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A device fingerprint capturing browser/device characteristics for fraud detection.
 *
 * <p>Fingerprints are hashed (SHA-256) for privacy. Reputation scores track
 * device trustworthiness over time (0 = malicious, 100 = trusted).</p>
 *
 * @since 0.3.0 (Sprint 3.1)
 */
public class DeviceFingerprint {

    private UUID id;
    private String tenantId;
    private String fingerprintHash;  // SHA-256 of composite fingerprint
    private String customerId;
    private String browserFamily;
    private String osFamily;
    private String deviceType;       // DESKTOP, MOBILE, TABLET
    private String screenResolution;
    private Integer timezoneOffset;
    private String language;
    private String ipAddress;
    private String ipCountry;        // ISO 3166-1 alpha-2
    private String ipCity;
    private int reputationScore = 50; // default neutral
    private Instant firstSeenAt;
    private Instant lastSeenAt;
    private int timesSeen = 1;
    private boolean flagged;

    public DeviceFingerprint() {
        this.id = UUID.randomUUID();
        this.firstSeenAt = Instant.now();
        this.lastSeenAt = Instant.now();
    }

    /**
     * Records a new sighting of this device, updating last-seen and count.
     */
    public void recordSighting() {
        this.lastSeenAt = Instant.now();
        this.timesSeen++;
    }

    /**
     * Adjusts reputation score (clamped to 0-100).
     */
    public void adjustReputation(int delta) {
        this.reputationScore = Math.max(0, Math.min(100, this.reputationScore + delta));
    }

    public void flag() {
        this.flagged = true;
        this.reputationScore = Math.max(0, this.reputationScore - 20);
    }

    // --- Getters & Setters ---

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getFingerprintHash() { return fingerprintHash; }
    public void setFingerprintHash(String fingerprintHash) { this.fingerprintHash = fingerprintHash; }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public String getBrowserFamily() { return browserFamily; }
    public void setBrowserFamily(String browserFamily) { this.browserFamily = browserFamily; }

    public String getOsFamily() { return osFamily; }
    public void setOsFamily(String osFamily) { this.osFamily = osFamily; }

    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }

    public String getScreenResolution() { return screenResolution; }
    public void setScreenResolution(String screenResolution) { this.screenResolution = screenResolution; }

    public Integer getTimezoneOffset() { return timezoneOffset; }
    public void setTimezoneOffset(Integer timezoneOffset) { this.timezoneOffset = timezoneOffset; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getIpCountry() { return ipCountry; }
    public void setIpCountry(String ipCountry) { this.ipCountry = ipCountry; }

    public String getIpCity() { return ipCity; }
    public void setIpCity(String ipCity) { this.ipCity = ipCity; }

    public int getReputationScore() { return reputationScore; }
    public void setReputationScore(int reputationScore) { this.reputationScore = reputationScore; }

    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant firstSeenAt) { this.firstSeenAt = firstSeenAt; }

    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public int getTimesSeen() { return timesSeen; }
    public void setTimesSeen(int timesSeen) { this.timesSeen = timesSeen; }

    public boolean isFlagged() { return flagged; }
    public void setFlagged(boolean flagged) { this.flagged = flagged; }
}
