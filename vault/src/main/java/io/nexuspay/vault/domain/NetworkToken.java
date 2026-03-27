package io.nexuspay.vault.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A network token provisioned via Visa VTS, Mastercard MDES, or Amex
 * for a vaulted card. Network tokens improve authorization rates and
 * eliminate card-on-file expiration issues.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public class NetworkToken {

    private String id;
    private String vaultedCardId;
    private String tenantId;
    private NetworkType network;
    private String tokenReference;
    private String tokenLast4;
    private TokenState status;
    private String tokenExpiry;
    private Instant provisionedAt;
    private Instant lastUsedAt;
    private Instant suspendedAt;

    public static NetworkToken create(String vaultedCardId, String tenantId, NetworkType network,
                                      String tokenReference, String tokenLast4, String tokenExpiry) {
        NetworkToken nt = new NetworkToken();
        nt.id = "nt_" + UUID.randomUUID().toString().replace("-", "");
        nt.vaultedCardId = vaultedCardId;
        nt.tenantId = tenantId;
        nt.network = network;
        nt.tokenReference = tokenReference;
        nt.tokenLast4 = tokenLast4;
        nt.status = TokenState.PROVISIONED;
        nt.tokenExpiry = tokenExpiry;
        nt.provisionedAt = Instant.now();
        return nt;
    }

    public void activate() { this.status = TokenState.ACTIVE; }

    public void suspend() {
        this.status = TokenState.SUSPENDED;
        this.suspendedAt = Instant.now();
    }

    public void markDeleted() { this.status = TokenState.DELETED; }

    public void recordUsage() { this.lastUsedAt = Instant.now(); }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getVaultedCardId() { return vaultedCardId; }
    public void setVaultedCardId(String vaultedCardId) { this.vaultedCardId = vaultedCardId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public NetworkType getNetwork() { return network; }
    public void setNetwork(NetworkType network) { this.network = network; }

    public String getTokenReference() { return tokenReference; }
    public void setTokenReference(String tokenReference) { this.tokenReference = tokenReference; }

    public String getTokenLast4() { return tokenLast4; }
    public void setTokenLast4(String tokenLast4) { this.tokenLast4 = tokenLast4; }

    public TokenState getStatus() { return status; }
    public void setStatus(TokenState status) { this.status = status; }

    public String getTokenExpiry() { return tokenExpiry; }
    public void setTokenExpiry(String tokenExpiry) { this.tokenExpiry = tokenExpiry; }

    public Instant getProvisionedAt() { return provisionedAt; }
    public void setProvisionedAt(Instant provisionedAt) { this.provisionedAt = provisionedAt; }

    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public Instant getSuspendedAt() { return suspendedAt; }
    public void setSuspendedAt(Instant suspendedAt) { this.suspendedAt = suspendedAt; }
}
