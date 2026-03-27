package io.nexuspay.vault.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for the {@code network_tokens} table.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
@Entity
@Table(name = "network_tokens")
public class NetworkTokenEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "vaulted_card_id", nullable = false, length = 64)
    private String vaultedCardId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "network", nullable = false, length = 16)
    private String network;

    @Column(name = "token_reference", nullable = false, length = 256)
    private String tokenReference;

    @Column(name = "token_last4", length = 4)
    private String tokenLast4;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "token_expiry", length = 4)
    private String tokenExpiry;

    @Column(name = "provisioned_at", nullable = false)
    private Instant provisionedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getVaultedCardId() { return vaultedCardId; }
    public void setVaultedCardId(String vaultedCardId) { this.vaultedCardId = vaultedCardId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getNetwork() { return network; }
    public void setNetwork(String network) { this.network = network; }

    public String getTokenReference() { return tokenReference; }
    public void setTokenReference(String tokenReference) { this.tokenReference = tokenReference; }

    public String getTokenLast4() { return tokenLast4; }
    public void setTokenLast4(String tokenLast4) { this.tokenLast4 = tokenLast4; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getTokenExpiry() { return tokenExpiry; }
    public void setTokenExpiry(String tokenExpiry) { this.tokenExpiry = tokenExpiry; }

    public Instant getProvisionedAt() { return provisionedAt; }
    public void setProvisionedAt(Instant provisionedAt) { this.provisionedAt = provisionedAt; }

    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public Instant getSuspendedAt() { return suspendedAt; }
    public void setSuspendedAt(Instant suspendedAt) { this.suspendedAt = suspendedAt; }
}
