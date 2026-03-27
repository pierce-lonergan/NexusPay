package io.nexuspay.vault.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Merchant-facing token ({@code tok_xxx}) that references a vaulted card.
 * This is the identifier merchants use to charge a stored card without
 * handling raw PAN data.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public class VaultToken {

    private String id;
    private String vaultedCardId;
    private String tenantId;
    private Instant createdAt;

    public static VaultToken create(String tenantId, String vaultedCardId) {
        VaultToken token = new VaultToken();
        token.id = "tok_" + UUID.randomUUID().toString().replace("-", "");
        token.vaultedCardId = vaultedCardId;
        token.tenantId = tenantId;
        token.createdAt = Instant.now();
        return token;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getVaultedCardId() { return vaultedCardId; }
    public void setVaultedCardId(String vaultedCardId) { this.vaultedCardId = vaultedCardId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
