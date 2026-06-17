package io.nexuspay.iam.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "api_keys")
public class ApiKeyEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "key_hash", nullable = false, length = 256)
    private String keyHash;

    @Column(name = "key_prefix", nullable = false, length = 16)
    private String keyPrefix;

    @Column(length = 128)
    private String name;

    @Column(nullable = false, length = 32)
    private String role;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "is_live", nullable = false)
    private boolean live;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    // DX-5c lifecycle columns (V4036). All nullable: expires_at NULL = never expires (back-compat);
    // last_used_at NULL = never authenticated since the column was added (observability, fail-open);
    // replaced_by NULL = not rotated/superseded.
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "replaced_by", length = 64)
    private String replacedBy;

    protected ApiKeyEntity() {}

    /**
     * Back-compat 9-arg constructor (e.g. createApiKey, test fixtures). The three DX-5c lifecycle
     * fields default to {@code null}; populate them via the fuller constructor or the setters.
     */
    public ApiKeyEntity(String id, String keyHash, String keyPrefix, String name,
                         String role, String tenantId, boolean live,
                         Instant createdAt, Instant revokedAt) {
        this(id, keyHash, keyPrefix, name, role, tenantId, live, createdAt, revokedAt,
                null, null, null);
    }

    /**
     * Full DX-5c constructor including the lifecycle columns.
     */
    public ApiKeyEntity(String id, String keyHash, String keyPrefix, String name,
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

    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public void setReplacedBy(String replacedBy) { this.replacedBy = replacedBy; }
}
