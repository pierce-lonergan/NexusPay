package io.nexuspay.gateway.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A payment session grants restricted-scope access for client-side checkout flows.
 * The SDK authenticates using the session's {@code clientSecret} (a JWT), which limits
 * operations to confirming the specific payment associated with this session.
 *
 * <p>Sessions use <strong>lazy expiration</strong>: {@link #isExpired()} checks the
 * {@code expiresAt} timestamp against the current time on every read. No background
 * sweeper is needed.
 *
 * @since 0.3.5 (Sprint 3.5)
 */
public class PaymentSession {

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_COMPLETE = "complete";
    public static final String STATUS_EXPIRED = "expired";

    private final String id;
    private final String tenantId;
    private String paymentIntentId;
    private final String clientSecret;
    private final long amount;
    private final String currency;
    private String status;
    private final String customerId;
    private final List<String> allowedPaymentMethods;
    private final String successUrl;
    private final String cancelUrl;
    private final Map<String, Object> branding;
    private final Map<String, Object> metadata;
    private int tokenizeAttempts;
    private final Instant expiresAt;
    private final Instant createdAt;
    private Instant updatedAt;

    public PaymentSession(String id, String tenantId, String paymentIntentId, String clientSecret,
                          long amount, String currency, String status, String customerId,
                          List<String> allowedPaymentMethods, String successUrl, String cancelUrl,
                          Map<String, Object> branding, Map<String, Object> metadata,
                          int tokenizeAttempts, Instant expiresAt, Instant createdAt, Instant updatedAt) {
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

    /**
     * Checks whether this session has expired by comparing {@code expiresAt}
     * against the current time. This is the lazy expiration strategy — no
     * background sweeper required.
     */
    public boolean isExpired() {
        return STATUS_EXPIRED.equals(status) || Instant.now().isAfter(expiresAt);
    }

    /**
     * Returns {@code true} if the session is open and not expired.
     */
    public boolean isOpen() {
        return STATUS_OPEN.equals(status) && !isExpired();
    }

    public void markComplete(String paymentIntentId) {
        this.status = STATUS_COMPLETE;
        this.paymentIntentId = paymentIntentId;
        this.updatedAt = Instant.now();
    }

    public void markExpired() {
        this.status = STATUS_EXPIRED;
        this.updatedAt = Instant.now();
    }

    public int incrementTokenizeAttempts() {
        this.tokenizeAttempts++;
        this.updatedAt = Instant.now();
        return this.tokenizeAttempts;
    }

    // Getters

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
}
