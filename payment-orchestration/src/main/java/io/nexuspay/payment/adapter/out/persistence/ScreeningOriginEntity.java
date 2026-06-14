package io.nexuspay.payment.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Server-owned originating screening context for a created payment (B-029). Maps to
 * {@code payment_screening_origin} (V4022). RLS-isolated by {@code tenant_id}.
 *
 * <p>Records the TRUSTED {@code (tenantId, screeningMode)} a payment was created under so a
 * later {@code confirmPayment} re-screens with the same authority instead of re-deriving it
 * from the (client-shaped, re-classifiable) intent metadata blob.</p>
 */
@Entity
@Table(name = "payment_screening_origin")
public class ScreeningOriginEntity {

    @Id
    @Column(name = "gateway_payment_id")
    private String gatewayPaymentId;

    @Column(name = "tenant_id")
    private String tenantId;

    @Column(name = "screening_mode", nullable = false)
    private String screeningMode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ScreeningOriginEntity() {
    }

    public ScreeningOriginEntity(String gatewayPaymentId, String tenantId,
                                 String screeningMode, Instant createdAt) {
        this.gatewayPaymentId = gatewayPaymentId;
        this.tenantId = tenantId;
        this.screeningMode = screeningMode;
        this.createdAt = createdAt;
    }

    public String getGatewayPaymentId() { return gatewayPaymentId; }
    public String getTenantId() { return tenantId; }
    public String getScreeningMode() { return screeningMode; }
    public Instant getCreatedAt() { return createdAt; }
}
