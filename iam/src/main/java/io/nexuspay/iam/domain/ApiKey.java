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
    // DX-5c lifecycle fields. expiresAt == null => never expires (back-compat). lastUsedAt is
    // observability only (fail-open). replacedBy is set to the new key's id when this key is rotated.
    private final Instant expiresAt;
    private final Instant lastUsedAt;
    private final String replacedBy;

    /**
     * Back-compat 9-arg constructor (pre-DX-5c call sites). The three DX-5c lifecycle fields default
     * to {@code null}: no expiry, never used, not replaced — i.e. exactly the prior behaviour.
     */
    public ApiKey(String id, String keyHash, String keyPrefix, String name,
                  String role, String tenantId, boolean live,
                  Instant createdAt, Instant revokedAt) {
        this(id, keyHash, keyPrefix, name, role, tenantId, live, createdAt, revokedAt,
                null, null, null);
    }

    /**
     * Full DX-5c constructor including the lifecycle fields.
     */
    public ApiKey(String id, String keyHash, String keyPrefix, String name,
                  String role, String tenantId, boolean live,
                  Instant createdAt, Instant revokedAt,
                  Instant expiresAt, Instant lastUsedAt, String replacedBy) {
        this.id = id;
        this.keyHash = keyHash;
        this.keyPrefix = keyPrefix;
        this.name = name;
        this.role = role;
        this.tenantId = tenantId;
        this.live = live;
        this.createdAt = createdAt;
        this.revokedAt = revokedAt;
        this.expiresAt = expiresAt;
        this.lastUsedAt = lastUsedAt;
        this.replacedBy = replacedBy;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isActive() {
        return !isRevoked();
    }

    /**
     * DX-5c: a key is expired when it has an {@code expiresAt} deadline and {@code now} is at-or-after it.
     * A {@code null} {@code expiresAt} means the key never expires (back-compat). Fail-closed at the
     * boundary: at-or-after the deadline is expired (the deadline instant itself is already expired).
     */
    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /**
     * DX-5c: a key is usable when it is neither revoked nor expired at {@code now}.
     */
    public boolean isUsable(Instant now) {
        return !isRevoked() && !isExpired(now);
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
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public String getReplacedBy() { return replacedBy; }
}
