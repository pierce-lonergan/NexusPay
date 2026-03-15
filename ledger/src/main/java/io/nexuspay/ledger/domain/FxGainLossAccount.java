package io.nexuspay.ledger.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Tracks realized and unrealized FX gains/losses per currency pair.
 * Each merchant (tenant) has one account per traded currency pair.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
public class FxGainLossAccount {

    private final UUID id;
    private final String tenantId;
    private final String currencyPair;
    private final String accountId;
    private BigDecimal realizedGainLoss;
    private BigDecimal unrealizedGainLoss;
    private Instant lastCalculatedAt;

    public FxGainLossAccount(UUID id, String tenantId, String currencyPair,
                             String accountId, BigDecimal realizedGainLoss,
                             BigDecimal unrealizedGainLoss, Instant lastCalculatedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.currencyPair = currencyPair;
        this.accountId = accountId;
        this.realizedGainLoss = realizedGainLoss;
        this.unrealizedGainLoss = unrealizedGainLoss;
        this.lastCalculatedAt = lastCalculatedAt;
    }

    public static FxGainLossAccount create(String tenantId, String currencyPair, String accountId) {
        return new FxGainLossAccount(
                UUID.randomUUID(), tenantId, currencyPair, accountId,
                BigDecimal.ZERO, BigDecimal.ZERO, Instant.now()
        );
    }

    /**
     * Records a realized FX gain or loss from a completed conversion.
     *
     * @param amount positive for gain, negative for loss
     */
    public void recordRealized(BigDecimal amount) {
        this.realizedGainLoss = this.realizedGainLoss.add(amount);
        this.lastCalculatedAt = Instant.now();
    }

    /**
     * Updates the unrealized FX position based on current market rates.
     */
    public void updateUnrealized(BigDecimal amount) {
        this.unrealizedGainLoss = amount;
        this.lastCalculatedAt = Instant.now();
    }

    /**
     * Returns the net FX position (realized + unrealized).
     */
    public BigDecimal netPosition() {
        return realizedGainLoss.add(unrealizedGainLoss);
    }

    // Getters
    public UUID getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getCurrencyPair() { return currencyPair; }
    public String getAccountId() { return accountId; }
    public BigDecimal getRealizedGainLoss() { return realizedGainLoss; }
    public BigDecimal getUnrealizedGainLoss() { return unrealizedGainLoss; }
    public Instant getLastCalculatedAt() { return lastCalculatedAt; }
}
