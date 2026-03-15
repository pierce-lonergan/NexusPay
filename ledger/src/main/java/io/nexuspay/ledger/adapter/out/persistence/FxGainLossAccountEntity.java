package io.nexuspay.ledger.adapter.out.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for FX gain/loss tracking accounts.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
@Entity
@Table(name = "fx_gain_loss_accounts",
        uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "currency_pair"}))
public class FxGainLossAccountEntity {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "currency_pair", nullable = false, length = 7)
    private String currencyPair;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Column(name = "realized_gain_loss", nullable = false, precision = 18, scale = 4)
    private BigDecimal realizedGainLoss = BigDecimal.ZERO;

    @Column(name = "unrealized_gain_loss", nullable = false, precision = 18, scale = 4)
    private BigDecimal unrealizedGainLoss = BigDecimal.ZERO;

    @Column(name = "last_calculated_at", nullable = false)
    private Instant lastCalculatedAt;

    protected FxGainLossAccountEntity() {}

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getCurrencyPair() { return currencyPair; }
    public void setCurrencyPair(String currencyPair) { this.currencyPair = currencyPair; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public BigDecimal getRealizedGainLoss() { return realizedGainLoss; }
    public void setRealizedGainLoss(BigDecimal realizedGainLoss) { this.realizedGainLoss = realizedGainLoss; }
    public BigDecimal getUnrealizedGainLoss() { return unrealizedGainLoss; }
    public void setUnrealizedGainLoss(BigDecimal unrealizedGainLoss) { this.unrealizedGainLoss = unrealizedGainLoss; }
    public Instant getLastCalculatedAt() { return lastCalculatedAt; }
    public void setLastCalculatedAt(Instant lastCalculatedAt) { this.lastCalculatedAt = lastCalculatedAt; }
}
