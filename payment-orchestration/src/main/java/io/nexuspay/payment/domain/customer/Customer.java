package io.nexuspay.payment.domain.customer;

import io.nexuspay.common.id.PrefixedId;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregate root for a customer — the anchor of the saved-credential cluster (TEST-3a).
 *
 * <p>A customer is a tenant-scoped, soft-deletable record of an end party a merchant transacts with.
 * It carries a server-derived {@code livemode} flag (test vs live, stamped from the caller key mode at
 * create time and immutable thereafter) and a free-form {@code metadata} map. Mirrors the
 * {@code io.nexuspay.dispute.domain.Dispute} aggregate in shape and lifecycle conventions.</p>
 *
 * <p>This is a plain domain object: persistence is handled by the
 * {@code adapter.out.persistence.customer} layer, which maps it to/from the {@code customers} table.</p>
 *
 * @since TEST-3a
 */
public class Customer {

    private String id;
    private String tenantId;
    private boolean livemode;
    private String email;
    private String name;
    private String description;
    private Map<String, Object> metadata;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;

    public Customer() {
    }

    // -- Factory --

    /**
     * Creates a new customer under the caller's tenant. {@code livemode} is server-derived from the
     * caller key mode (NEVER client-supplied). The id is minted server-side via
     * {@link PrefixedId#customer()}; {@code createdAt} and {@code updatedAt} are stamped to now.
     */
    public static Customer create(String tenantId, boolean livemode, String email, String name,
                                  String description, Map<String, Object> metadata) {
        Customer c = new Customer();
        c.id = PrefixedId.customer();
        c.tenantId = tenantId;
        c.livemode = livemode;
        c.email = email;
        c.name = name;
        c.description = description;
        c.metadata = metadata != null ? new LinkedHashMap<>(metadata) : null;
        c.createdAt = Instant.now();
        c.updatedAt = c.createdAt;
        c.deletedAt = null;
        return c;
    }

    // -- Mutators --

    /**
     * Applies a partial update to the mutable fields (email/name/description/metadata) and bumps
     * {@code updatedAt}. A {@code null} argument leaves that field unchanged. {@code livemode} is
     * deliberately NOT mutable (it is server-derived at create time).
     */
    public void applyUpdate(String email, String name, String description, Map<String, Object> metadata) {
        if (email != null) {
            this.email = email;
        }
        if (name != null) {
            this.name = name;
        }
        if (description != null) {
            this.description = description;
        }
        if (metadata != null) {
            this.metadata = new LinkedHashMap<>(metadata);
        }
        this.updatedAt = Instant.now();
    }

    /**
     * Soft-deletes the customer: stamps {@code deletedAt} and bumps {@code updatedAt}. The row remains
     * in the table but is excluded from the tenant-scoped finders, so it no longer appears in
     * retrieve/list (no resurrection).
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

    public boolean isLivemode() { return livemode; }
    public void setLivemode(boolean livemode) { this.livemode = livemode; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
}
