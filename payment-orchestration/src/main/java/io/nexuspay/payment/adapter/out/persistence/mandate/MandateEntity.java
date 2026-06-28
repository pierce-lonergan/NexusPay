package io.nexuspay.payment.adapter.out.persistence.mandate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * JPA entity mapped to the {@code mandates} table (V4040). Tenant-isolated by {@code tenant_id}. Mirrors
 * {@code PaymentMethodEntity} (jsonb metadata handling).
 *
 * <p>Unlike the payment_methods template there is NO {@code deleted_at} column — a mandate is never soft
 * deleted. {@code revoke} flips {@code status} to {@code INACTIVE} and stamps {@code revoked_at}, but the
 * row stays retrievable.</p>
 *
 * @since TEST-3d
 */
@Entity
@Table(name = "mandates")
public class MandateEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "payment_method_id", nullable = false, length = 64)
    private String paymentMethodId;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(length = 64)
    private String scenario;

    @Column(nullable = false)
    private boolean livemode;

    // Serialized metadata map. Hibernate 6 maps the String through the JSON jdbc type to the jsonb
    // column, matching the migration's jsonb type.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadataJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    // -- Getters & setters --

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public String getPaymentMethodId() { return paymentMethodId; }
    public void setPaymentMethodId(String paymentMethodId) { this.paymentMethodId = paymentMethodId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getScenario() { return scenario; }
    public void setScenario(String scenario) { this.scenario = scenario; }

    public boolean isLivemode() { return livemode; }
    public void setLivemode(boolean livemode) { this.livemode = livemode; }

    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
}
