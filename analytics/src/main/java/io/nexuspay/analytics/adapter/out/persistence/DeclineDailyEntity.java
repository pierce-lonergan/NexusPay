package io.nexuspay.analytics.adapter.out.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * JPA entity for the {@code analytics.decline_daily} table.
 * <p>Daily decline aggregates broken down by PSP connector, decline code/category,
 * and optional card/issuer dimensions.</p>
 *
 * @since 0.3.0 (Sprint 3.6)
 */
@Entity
@Table(name = "decline_daily", schema = "analytics")
public class DeclineDailyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "bucket_date", nullable = false)
    private LocalDate bucketDate;

    @Column(name = "psp_connector", nullable = false)
    private String pspConnector;

    @Column(name = "decline_code", nullable = false)
    private String declineCode;

    @Column(name = "decline_category", nullable = false)
    private String declineCategory;

    @Column(name = "card_brand")
    private String cardBrand;

    @Column(name = "issuing_region")
    private String issuingRegion;

    @Column(name = "issuer_name")
    private String issuerName;

    @Column(name = "total_count", nullable = false)
    private int totalCount;

    @Column(name = "total_volume", nullable = false)
    private BigDecimal totalVolume;

    protected DeclineDailyEntity() {}

    public DeclineDailyEntity(UUID id, String tenantId, LocalDate bucketDate,
                              String pspConnector, String declineCode, String declineCategory,
                              String cardBrand, String issuingRegion, String issuerName,
                              int totalCount, BigDecimal totalVolume) {
        this.id = id;
        this.tenantId = tenantId;
        this.bucketDate = bucketDate;
        this.pspConnector = pspConnector;
        this.declineCode = declineCode;
        this.declineCategory = declineCategory;
        this.cardBrand = cardBrand;
        this.issuingRegion = issuingRegion;
        this.issuerName = issuerName;
        this.totalCount = totalCount;
        this.totalVolume = totalVolume;
    }

    // --- Getters & Setters ---

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public LocalDate getBucketDate() { return bucketDate; }
    public void setBucketDate(LocalDate bucketDate) { this.bucketDate = bucketDate; }
    public String getPspConnector() { return pspConnector; }
    public void setPspConnector(String pspConnector) { this.pspConnector = pspConnector; }
    public String getDeclineCode() { return declineCode; }
    public void setDeclineCode(String declineCode) { this.declineCode = declineCode; }
    public String getDeclineCategory() { return declineCategory; }
    public void setDeclineCategory(String declineCategory) { this.declineCategory = declineCategory; }
    public String getCardBrand() { return cardBrand; }
    public void setCardBrand(String cardBrand) { this.cardBrand = cardBrand; }
    public String getIssuingRegion() { return issuingRegion; }
    public void setIssuingRegion(String issuingRegion) { this.issuingRegion = issuingRegion; }
    public String getIssuerName() { return issuerName; }
    public void setIssuerName(String issuerName) { this.issuerName = issuerName; }
    public int getTotalCount() { return totalCount; }
    public void setTotalCount(int totalCount) { this.totalCount = totalCount; }
    public BigDecimal getTotalVolume() { return totalVolume; }
    public void setTotalVolume(BigDecimal totalVolume) { this.totalVolume = totalVolume; }
}
