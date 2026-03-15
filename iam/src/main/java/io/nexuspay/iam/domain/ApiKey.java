package io.nexuspay.iam.domain;

import java.time.Instant;

/**
 * API key domain entity.
 * Keys are formatted as sk_test_{random32} / sk_live_{random32}.
 * Only the bcrypt hash is stored — the full key is shown once at creation.
 */
public class ApiKey {

    private final String id;
    private final String keyHash;
    private final String keyPrefix;
    private final String name;
    private final String role;
    private final String tenantId;
    private final boolean live;
    private final Instant createdAt;
    private Instant revokedAt;

    public ApiKey(String id, String keyHash, String keyPrefix, String name,
                  String role, String tenantId, boolean live,
                  Instant createdAt, Instant revokedAt) {
        this.id = id;
        this.keyHash = keyHash;
        this.keyPrefix = keyPrefix;
        this.name = name;
        this.role = role;
        this.tenantId = tenantId;
        this.live = live;
        this.createdAt = createdAt;
        this.revokedAt = revokedAt;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isActive() {
        return !isRevoked();
    }

    public void revoke() {
        this.revokedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getKeyHash() { return keyHash; }
    public String getKeyPrefix() { return keyPrefix; }
    public String getName() { return name; }
    public String getRole() { return role; }
    public String getTenantId() { return tenantId; }
    public boolean isLive() { return live; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
