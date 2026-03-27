package io.nexuspay.vault.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for the {@code vault_tokens} table.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
@Entity
@Table(name = "vault_tokens")
public class VaultTokenEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "vaulted_card_id", nullable = false, length = 64)
    private String vaultedCardId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getVaultedCardId() { return vaultedCardId; }
    public void setVaultedCardId(String vaultedCardId) { this.vaultedCardId = vaultedCardId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
