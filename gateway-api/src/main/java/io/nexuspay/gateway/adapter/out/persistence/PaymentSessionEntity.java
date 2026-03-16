package io.nexuspay.gateway.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * JPA entity for the {@code payment_sessions} table.
 *
 * @since 0.3.5 (Sprint 3.5)
 */
@Entity
@Table(name = "payment_sessions")
public class PaymentSessionEntity {

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "payment_intent_id")
    private String paymentIntentId;

    @Column(name = "client_secret", nullable = false, unique = true)
    private String clientSecret;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "customer_id")
    private String customerId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_payment_methods", columnDefinition = "jsonb")
    private List<String> allowedPaymentMethods;

    @Column(name = "success_url")
    private String successUrl;

    @Column(name = "cancel_url")
    private String cancelUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> branding;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "tokenize_attempts", nullable = false)
    private int tokenizeAttempts;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentSessionEntity() {
    }

    public PaymentSessionEntity(String id, String tenantId, String paymentIntentId,
                                 String clientSecret, long amount, String currency,
                                 String status, String customerId,
                                 List<String> allowedPaymentMethods,
                                 String successUrl, String cancelUrl,
                                 Map<String, Object> branding, Map<String, Object> metadata,
                                 int tokenizeAttempts, Instant expiresAt,
                                 Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.paymentIntentId = paymentIntentId;
        this.clientSecret = clientSecret;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.customerId = customerId;
        this.allowedPaymentMethods = allowedPaymentMethods;
        this.successUrl = successUrl;
        this.cancelUrl = cancelUrl;
        this.branding = branding;
        this.metadata = metadata;
        this.tokenizeAttempts = tokenizeAttempts;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getPaymentIntentId() { return paymentIntentId; }
    public String getClientSecret() { return clientSecret; }
    public long getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public String getCustomerId() { return customerId; }
    public List<String> getAllowedPaymentMethods() { return allowedPaymentMethods; }
    public String getSuccessUrl() { return successUrl; }
    public String getCancelUrl() { return cancelUrl; }
    public Map<String, Object> getBranding() { return branding; }
    public Map<String, Object> getMetadata() { return metadata; }
    public int getTokenizeAttempts() { return tokenizeAttempts; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public void setPaymentIntentId(String paymentIntentId) {
        this.paymentIntentId = paymentIntentId;
        this.updatedAt = Instant.now();
    }

    public void setTokenizeAttempts(int tokenizeAttempts) {
        this.tokenizeAttempts = tokenizeAttempts;
        this.updatedAt = Instant.now();
    }
}
