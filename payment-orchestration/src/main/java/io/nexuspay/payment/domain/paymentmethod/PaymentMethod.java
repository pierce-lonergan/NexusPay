package io.nexuspay.payment.domain.paymentmethod;

import io.nexuspay.common.id.PrefixedId;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregate root for a saved, multi-use payment method — the chargeable credential of the
 * saved-credential cluster (TEST-3b). Mirrors {@link io.nexuspay.payment.domain.customer.Customer} in
 * shape and lifecycle conventions.
 *
 * <p>A payment method is a tenant-scoped, soft-deletable record attached to a {@code cus_} customer in
 * the SAME tenant. It carries a server-derived {@code livemode} flag (must equal the target customer's
 * livemode, enforced at attach time) and a free-form {@code metadata} map.</p>
 *
 * <h3>PCI (SEC-BATCH-3) — the load-bearing constraint</h3>
 * <p>This aggregate NEVER stores a raw PAN or any card secret. It holds ONLY display fields
 * ({@code brand}/{@code last4}/{@code expMonth}/{@code expYear}/{@code funding}) plus an OPAQUE
 * {@code credentialRef} string — the chargeable handle resolved at charge time (3c). There is
 * deliberately NO card-number / cvc field.</p>
 *
 * <p>{@code livemode}, {@code customerId}, and {@code credentialRef} are immutable after create (3b has
 * no update endpoint — only attach and detach). This is a plain domain object: persistence is handled by
 * the {@code adapter.out.persistence.paymentmethod} layer.</p>
 *
 * @since TEST-3b
 */
public class PaymentMethod {

    private String id;
    private String tenantId;
    private String customerId;
    private boolean livemode;
    private String type;
    private String brand;
    private String last4;
    private Integer expMonth;
    private Integer expYear;
    private String funding;
    private String credentialRef;
    private Map<String, Object> metadata;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;

    public PaymentMethod() {
    }

    // -- Factory --

    /**
     * Creates a new saved payment method under the caller's tenant, attached to {@code customerId} (a
     * {@code cus_} already validated tenant-scoped + livemode-matched at the service boundary). The id is
     * minted server-side via {@link PrefixedId#paymentMethod()}; {@code createdAt}/{@code updatedAt} are
     * stamped to now; {@code deletedAt} is null (attached).
     *
     * <p>NO PAN is ever a parameter — only display fields + the opaque {@code credentialRef}.</p>
     */
    public static PaymentMethod create(String tenantId, String customerId, boolean livemode, String type,
                                       String brand, String last4, Integer expMonth, Integer expYear,
                                       String funding, String credentialRef, Map<String, Object> metadata) {
        PaymentMethod pm = new PaymentMethod();
        pm.id = PrefixedId.paymentMethod();
        pm.tenantId = tenantId;
        pm.customerId = customerId;
        pm.livemode = livemode;
        pm.type = type;
        pm.brand = brand;
        pm.last4 = last4;
        pm.expMonth = expMonth;
        pm.expYear = expYear;
        pm.funding = funding;
        pm.credentialRef = credentialRef;
        pm.metadata = metadata != null ? new LinkedHashMap<>(metadata) : null;
        pm.createdAt = Instant.now();
        pm.updatedAt = pm.createdAt;
        pm.deletedAt = null;
        return pm;
    }

    // -- Mutators --

    /**
     * DETACH: soft-deletes the saved method (stamps {@code deletedAt} and bumps {@code updatedAt}). The
     * row remains but is excluded from the tenant-scoped finders, so it no longer surfaces from
     * retrieve or the customer's list (no resurrection).
     */
    public void markDeleted() {
        Instant now = Instant.now();
        this.deletedAt = now;
        this.updatedAt = now;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

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

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
}
