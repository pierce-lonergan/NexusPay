package io.nexuspay.payment.adapter.out.persistence.routing;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * JPA entity for PSP fee models.
 *
 * @since 0.3.0 (Sprint 3.3)
 * @since 0.3.1 (GAP-049 — card-brand-specific columns)
 */
@Entity
@Table(name = "psp_fee_models")
public class PspFeeModelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "psp_connector", nullable = false)
    private String pspConnector;

    @Column(name = "fee_type", nullable = false)
    private String feeType;

    @Column(name = "per_tx_fee")
    private BigDecimal perTxFee;

    @Column(name = "percentage_fee")
    private BigDecimal percentageFee;

    @Column(name = "interchange_markup_bps")
    private Integer interchangeMarkupBps;

    @Column(name = "scheme_fee_bps")
    private Integer schemeFeeBps;

    @Column(nullable = false)
    private String currency;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "card_brand")
    private String cardBrand;

    @Column(name = "card_type")
    private String cardType;

    @Column(name = "is_domestic")
    private Boolean isDomestic;

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPspConnector() { return pspConnector; }
    public void setPspConnector(String pspConnector) { this.pspConnector = pspConnector; }
    public String getFeeType() { return feeType; }
    public void setFeeType(String feeType) { this.feeType = feeType; }
    public BigDecimal getPerTxFee() { return perTxFee; }
    public void setPerTxFee(BigDecimal perTxFee) { this.perTxFee = perTxFee; }
    public BigDecimal getPercentageFee() { return percentageFee; }
    public void setPercentageFee(BigDecimal percentageFee) { this.percentageFee = percentageFee; }
    public Integer getInterchangeMarkupBps() { return interchangeMarkupBps; }
    public void setInterchangeMarkupBps(Integer interchangeMarkupBps) { this.interchangeMarkupBps = interchangeMarkupBps; }
    public Integer getSchemeFeeBps() { return schemeFeeBps; }
    public void setSchemeFeeBps(Integer schemeFeeBps) { this.schemeFeeBps = schemeFeeBps; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public LocalDate getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(LocalDate effectiveTo) { this.effectiveTo = effectiveTo; }
    public String getCardBrand() { return cardBrand; }
    public void setCardBrand(String cardBrand) { this.cardBrand = cardBrand; }
    public String getCardType() { return cardType; }
    public void setCardType(String cardType) { this.cardType = cardType; }
    public Boolean getIsDomestic() { return isDomestic; }
    public void setIsDomestic(Boolean isDomestic) { this.isDomestic = isDomestic; }
}
