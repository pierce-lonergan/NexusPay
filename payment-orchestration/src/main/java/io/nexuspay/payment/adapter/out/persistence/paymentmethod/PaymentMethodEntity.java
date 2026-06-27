package io.nexuspay.payment.adapter.out.persistence.paymentmethod;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * JPA entity mapped to the {@code payment_methods} table (V4039). Tenant-isolated by {@code tenant_id};
 * soft-deletable via {@code deleted_at}. Mirrors {@code CustomerEntity} (jsonb metadata handling).
 *
 * <h3>PCI (SEC-BATCH-3)</h3>
 * <p>There is deliberately NO {@code card_number}/{@code pan}/{@code cvc} column — only display fields
 * ({@code brand}/{@code last4}/{@code exp_*}/{@code funding}) and the OPAQUE {@code credential_ref}.</p>
 *
 * @since TEST-3b
 */
@Entity
@Table(name = "payment_methods")
public class PaymentMethodEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(nullable = false)
    private boolean livemode;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(length = 32)
    private String brand;

    @Column(length = 4)
    private String last4;

    @Column(name = "exp_month")
    private Integer expMonth;

    @Column(name = "exp_year")
    private Integer expYear;

    @Column(length = 32)
    private String funding;

    // OPAQUE chargeable handle resolved at charge time (3c). NOT a PAN — never a card secret.
    @Column(name = "credential_ref", nullable = false, length = 255)
    private String credentialRef;

    // Serialized metadata map. Hibernate 6 maps the String through the JSON jdbc type to the jsonb
    // column, matching the migration's jsonb type.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadataJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    // -- Getters & setters --

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public boolean isLivemode() { return livemode; }
    public void setLivemode(boolean livemode) { this.livemode = livemode; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public String getLast4() { return last4; }
    public void setLast4(String last4) { this.last4 = last4; }

    public Integer getExpMonth() { return expMonth; }
    public void setExpMonth(Integer expMonth) { this.expMonth = expMonth; }

    public Integer getExpYear() { return expYear; }
    public void setExpYear(Integer expYear) { this.expYear = expYear; }

    public String getFunding() { return funding; }
    public void setFunding(String funding) { this.funding = funding; }

    public String getCredentialRef() { return credentialRef; }
    public void setCredentialRef(String credentialRef) { this.credentialRef = credentialRef; }

    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
}
