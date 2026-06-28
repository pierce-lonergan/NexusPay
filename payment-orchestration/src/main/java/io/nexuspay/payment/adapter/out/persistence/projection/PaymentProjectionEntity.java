package io.nexuspay.payment.adapter.out.persistence.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * GAP-076 (critique v3 F1): JPA entity mapped to the {@code payments} READ-MODEL projection table
 * (V4041). Mirrors {@code CustomerEntity}'s column-mapping style.
 *
 * <p>{@code createdAt} is {@code updatable = false} — it is INSERTed once from the first write's
 * {@code PaymentResponse.createdAt} and never overwritten on a later upsert, so forward updates never
 * rewrite the birth time (the idempotency guarantee).</p>
 *
 * <p>NO PAN / card data / secret column lives on this entity (PCI). It carries only the denormalized,
 * list-displayable fields.</p>
 */
@Entity
@Table(name = "payments")
public class PaymentProjectionEntity {

    @Id
    @Column(name = "payment_id", length = 64)
    private String paymentId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false)
    private boolean livemode;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false)
    private long amount;

    @Column(length = 8)
    private String currency;

    @Column(name = "capture_method", length = 16)
    private String captureMethod;

    @Column(name = "customer_id", length = 64)
    private String customerId;

    @Column(name = "connector_name", length = 64)
    private String connectorName;

    @Column(name = "error_code", length = 128)
    private String errorCode;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    // Set ONCE on insert (from PaymentResponse.createdAt); never overwritten on a later upsert.
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // -- Getters & setters --

    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public boolean isLivemode() { return livemode; }
    public void setLivemode(boolean livemode) { this.livemode = livemode; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getCaptureMethod() { return captureMethod; }
    public void setCaptureMethod(String captureMethod) { this.captureMethod = captureMethod; }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public String getConnectorName() { return connectorName; }
    public void setConnectorName(String connectorName) { this.connectorName = connectorName; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
