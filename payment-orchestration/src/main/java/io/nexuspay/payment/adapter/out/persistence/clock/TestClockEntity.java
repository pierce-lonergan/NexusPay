package io.nexuspay.payment.adapter.out.persistence.clock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * GAP-078 (critique v3 F5): JPA entity for the per-tenant TEST CLOCK ({@code test_clocks}, V4042). Mirrors
 * {@code PaymentProjectionEntity}'s column-mapping style.
 *
 * <p>{@code tenantId} is the PK (one clock per tenant). {@code fixedAt} is the frozen instant; a row's mere
 * existence means "frozen". {@code createdAt} is {@code updatable = false} (set once on insert);
 * {@code updatedAt} is bumped on every re-set.</p>
 *
 * @since GAP-078
 */
@Entity
@Table(name = "test_clocks")
public class TestClockEntity {

    @Id
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(name = "fixed_at", nullable = false)
    private Instant fixedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Instant getFixedAt() { return fixedAt; }
    public void setFixedAt(Instant fixedAt) { this.fixedAt = fixedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
