package io.nexuspay.billing.domain;

import io.nexuspay.common.id.PrefixedId;

import java.time.Instant;
import java.util.Map;

/**
 * A billable product in the catalog.
 *
 * <p>Products represent what is being sold. They are associated with one or
 * more {@link Price} records that define how much to charge.</p>
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
public class Product {

    private String id;
    private String tenantId;
    private String name;
    private String description;
    private boolean active;
    private Map<String, Object> metadata;
    private Instant createdAt;
    private Instant updatedAt;

    public Product() {
    }

    public static Product create(String tenantId, String name, String description,
                                  Map<String, Object> metadata) {
        Product p = new Product();
        p.id = PrefixedId.product();
        p.tenantId = tenantId;
        p.name = name;
        p.description = description;
        p.active = true;
        p.metadata = metadata;
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        return p;
    }

    public void deactivate() {
        this.active = false;
        this.updatedAt = Instant.now();
    }

    public void activate() {
        this.active = true;
        this.updatedAt = Instant.now();
    }

    // -- Getters & setters --

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
