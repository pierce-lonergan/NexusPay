package io.nexuspay.payment.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * INT-1: server-owned merchant correlation metadata for outbound-webhook enrichment. Maps to
 * {@code payment_webhook_metadata} (V4030). RLS-isolated by {@code tenant_id}.
 *
 * <p>Persisted at payment-create time (the {@code GatedPaymentGateway.doCreate} chokepoint) keyed by
 * the gateway payment id, scoped to the server-derived trusted tenant. Read at webhook delivery and
 * placed in the canonical envelope's {@code data.metadata}. Stores ONLY the merchant correlation map
 * (e.g. {@code userId}/{@code packId}) — NEVER {@code payment_method_data}/PAN/card data, which the
 * write-side {@code sanitize()} strips. The serialized map is size- and key-count-capped at write
 * time.</p>
 *
 * <p>Mirrors {@link ScreeningOriginEntity} (V4022) in shape and lifecycle.</p>
 */
@Entity
@Table(name = "payment_webhook_metadata")
public class PaymentWebhookMetadataEntity {

    @Id
    @Column(name = "gateway_payment_id")
    private String gatewayPaymentId;

    @Column(name = "tenant_id")
    private String tenantId;

    // Serialized merchant correlation map (sanitized + capped before persist). Hibernate 6 maps the
    // String through the JSON jdbc type to the jsonb column, matching the migration's jsonb type.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", columnDefinition = "jsonb", nullable = false)
    private String metadataJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentWebhookMetadataEntity() {
    }

    public PaymentWebhookMetadataEntity(String gatewayPaymentId, String tenantId,
                                        String metadataJson, Instant createdAt) {
        this.gatewayPaymentId = gatewayPaymentId;
        this.tenantId = tenantId;
        this.metadataJson = metadataJson;
        this.createdAt = createdAt;
    }

    public String getGatewayPaymentId() { return gatewayPaymentId; }
    public String getTenantId() { return tenantId; }
    public String getMetadataJson() { return metadataJson; }
    public Instant getCreatedAt() { return createdAt; }
}
