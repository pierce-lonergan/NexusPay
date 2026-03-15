package io.nexuspay.fraud.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for device_fingerprints table.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
@Entity
@Table(name = "device_fingerprints")
public class DeviceFingerprintEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "fingerprint_hash", nullable = false)
    private String fingerprintHash;

    @Column(name = "customer_id")
    private String customerId;

    @Column(name = "browser_family")
    private String browserFamily;

    @Column(name = "os_family")
    private String osFamily;

    @Column(name = "device_type")
    private String deviceType;

    @Column(name = "screen_resolution")
    private String screenResolution;

    @Column(name = "timezone_offset")
    private Integer timezoneOffset;

    @Column(name = "language")
    private String language;

    @Column(name = "ip_address", columnDefinition = "inet")
    private String ipAddress;

    @Column(name = "ip_country")
    private String ipCountry;

    @Column(name = "ip_city")
    private String ipCity;

    @Column(name = "reputation_score", nullable = false)
    private int reputationScore;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "times_seen", nullable = false)
    private int timesSeen;

    @Column(name = "flagged", nullable = false)
    private boolean flagged;

    // --- Getters & Setters ---

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getFingerprintHash() { return fingerprintHash; }
    public void setFingerprintHash(String h) { this.fingerprintHash = h; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getBrowserFamily() { return browserFamily; }
    public void setBrowserFamily(String b) { this.browserFamily = b; }
    public String getOsFamily() { return osFamily; }
    public void setOsFamily(String o) { this.osFamily = o; }
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String d) { this.deviceType = d; }
    public String getScreenResolution() { return screenResolution; }
    public void setScreenResolution(String s) { this.screenResolution = s; }
    public Integer getTimezoneOffset() { return timezoneOffset; }
    public void setTimezoneOffset(Integer t) { this.timezoneOffset = t; }
    public String getLanguage() { return language; }
    public void setLanguage(String l) { this.language = l; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ip) { this.ipAddress = ip; }
    public String getIpCountry() { return ipCountry; }
    public void setIpCountry(String c) { this.ipCountry = c; }
    public String getIpCity() { return ipCity; }
    public void setIpCity(String c) { this.ipCity = c; }
    public int getReputationScore() { return reputationScore; }
    public void setReputationScore(int r) { this.reputationScore = r; }
    public Instant getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Instant f) { this.firstSeenAt = f; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant l) { this.lastSeenAt = l; }
    public int getTimesSeen() { return timesSeen; }
    public void setTimesSeen(int t) { this.timesSeen = t; }
    public boolean isFlagged() { return flagged; }
    public void setFlagged(boolean f) { this.flagged = f; }
}
