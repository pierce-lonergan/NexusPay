package io.nexuspay.payment.adapter.out.persistence.fx;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * JPA entity for PSP currency capabilities.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
@Entity
@Table(name = "currency_capabilities",
        uniqueConstraints = @UniqueConstraint(columnNames = {"psp_connector", "currency_code"}))
public class CurrencyCapabilityEntity {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "psp_connector", nullable = false, length = 50)
    private String pspConnector;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "supports_presentment", nullable = false)
    private boolean supportsPresentment = true;

    @Column(name = "supports_settlement", nullable = false)
    private boolean supportsSettlement = false;

    @Column(name = "supports_dcc", nullable = false)
    private boolean supportsDcc = false;

    @Column(name = "min_amount", precision = 18, scale = 2)
    private BigDecimal minAmount;

    @Column(name = "max_amount", precision = 18, scale = 2)
    private BigDecimal maxAmount;

    @Column(nullable = false)
    private boolean enabled = true;

    protected CurrencyCapabilityEntity() {}

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getPspConnector() { return pspConnector; }
    public void setPspConnector(String pspConnector) { this.pspConnector = pspConnector; }
    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
    public boolean isSupportsPresentment() { return supportsPresentment; }
    public void setSupportsPresentment(boolean supportsPresentment) { this.supportsPresentment = supportsPresentment; }
    public boolean isSupportsSettlement() { return supportsSettlement; }
    public void setSupportsSettlement(boolean supportsSettlement) { this.supportsSettlement = supportsSettlement; }
    public boolean isSupportsDcc() { return supportsDcc; }
    public void setSupportsDcc(boolean supportsDcc) { this.supportsDcc = supportsDcc; }
    public BigDecimal getMinAmount() { return minAmount; }
    public void setMinAmount(BigDecimal minAmount) { this.minAmount = minAmount; }
    public BigDecimal getMaxAmount() { return maxAmount; }
    public void setMaxAmount(BigDecimal maxAmount) { this.maxAmount = maxAmount; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
