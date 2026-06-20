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

    // DX-5c-ii scope column (V4037). NULL/empty = UNRESTRICTED (role-based, back-compat); otherwise a
    // comma-delimited subset of the io.nexuspay.common.api.ApiScope vocabulary that NARROWS the role.
    // columnDefinition = "TEXT" to match the V4037 column — a plain String maps to varchar(255) and would
    // fail ddl-auto=validate (L-025), as every other TEXT-backed String column here does (e.g.
    // WebhookDeliveryEntity.canonicalBody).
    @Column(name = "scopes", columnDefinition = "TEXT")
    private String scopes;

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
     * DX-5c 12-arg constructor (lifecycle columns, no scopes). Preserved for existing call sites and
     * test fixtures; {@code scopes} defaults to {@code null} (UNRESTRICTED). Set scopes via the fuller
     * constructor or {@link #setScopes(String)}.
     */
    public ApiKeyEntity(String id, String keyHash, String keyPrefix, String name,
                         String role, String tenantId, boolean live,
                         Instant createdAt, Instant revokedAt,
                         Instant expiresAt, Instant lastUsedAt, String replacedBy) {
        this(id, keyHash, keyPrefix, name, role, tenantId, live, createdAt, revokedAt,
                expiresAt, lastUsedAt, replacedBy, null);
    }

    /**
     * Full DX-5c-ii constructor including the {@code scopes} column. {@code scopes} is a comma-delimited
     * subset of the ApiScope vocabulary, or {@code null}/empty for an UNRESTRICTED (role-based) key.
     */
    public ApiKeyEntity(String id, String keyHash, String keyPrefix, String name,
                        String role, String tenantId, boolean live,
                        Instant createdAt, Instant revokedAt,
                        Instant expiresAt, Instant lastUsedAt, String replacedBy,
                        String scopes) {
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
        this.scopes = scopes;
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
    public String getScopes() { return scopes; }

    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public void setReplacedBy(String replacedBy) { this.replacedBy = replacedBy; }
    public void setScopes(String scopes) { this.scopes = scopes; }
}
