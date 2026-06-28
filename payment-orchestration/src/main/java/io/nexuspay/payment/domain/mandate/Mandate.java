package io.nexuspay.payment.domain.mandate;

import io.nexuspay.common.id.PrefixedId;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregate root for a MANDATE / CONSENT record — the recorded off-session consent of the
 * saved-credential cluster (TEST-3d). Mirrors {@link io.nexuspay.payment.domain.paymentmethod.PaymentMethod}
 * in shape and lifecycle conventions.
 *
 * <p>A mandate records a customer's stored consent to be charged off-session (recurring / single-use) with
 * a saved method. It is the consent record an off-session charge's {@code mandate_id} references: a cited
 * mandate is a real consent gate (validated tenant-scoped + ACTIVE + pm-matching by
 * {@code OffSessionChargeService}), not a dangling string.</p>
 *
 * <h3>Lifecycle</h3>
 * <p>{@code status} is one of {@link #STATUS_PENDING} / {@link #STATUS_ACTIVE} / {@link #STATUS_INACTIVE}.
 * {@code create} stamps {@link #STATUS_ACTIVE} (a recorded consent is active the moment it is recorded —
 * {@code PENDING} is reserved in the vocabulary for a future async-confirmation flow but is never produced
 * by the 3d create path). {@link #revoke()} flips to {@link #STATUS_INACTIVE} and stamps {@code revokedAt}.
 * Revoke is deliberately NOT a soft delete: there is no {@code deletedAt} and the tenant-scoped finder does
 * NOT filter on status, so a revoked mandate STAYS RETRIEVABLE (this is the key divergence from the
 * payment_methods template, which excludes detached rows).</p>
 *
 * <p>{@code customerId}, {@code paymentMethodId}, and {@code livemode} are immutable after create. This is a
 * plain domain object: persistence is handled by the {@code adapter.out.persistence.mandate} layer.</p>
 *
 * @since TEST-3d
 */
public class Mandate {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";

    public static final String TYPE_MULTI_USE = "MULTI_USE";

    /**
     * SINGLE_USE is a recorded DESCRIPTIVE hint in 3d, NOT an enforced control. A SINGLE_USE mandate is
     * accepted and persisted, but it is never self-consumed: it stays {@link #STATUS_ACTIVE} after an
     * off-session charge, and {@code MandateService.validateActiveForCharge} (the consent gate) checks only
     * tenant + ACTIVE + matching {@code paymentMethodId} — it does NOT consider {@code type}. So a SINGLE_USE
     * mandate can be cited on more than one off-session charge (Stripe parity: the mandate resource does not
     * self-consume). Integrators must NOT assume single-use enforcement; to stop further use, {@link #revoke()}
     * the mandate. Single-use consumption (e.g. a terminal CONSUMED transition after a SUCCEEDED charge) is a
     * deliberately deferred later increment.
     */
    public static final String TYPE_SINGLE_USE = "SINGLE_USE";

    private String id;
    private String tenantId;
    private String customerId;
    private String paymentMethodId;
    private String status;
    private String type;
    private String scenario;
    private boolean livemode;
    private Map<String, Object> metadata;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant revokedAt;

    public Mandate() {
    }

    // -- Factory --

    /**
     * Creates a new mandate under the caller's tenant. The {@code customerId} is DERIVED from the resolved
     * {@code pm_}'s owner (never client-supplied) and the {@code paymentMethodId} is the tenant-validated
     * {@code pm_}. The id is minted server-side via {@link PrefixedId#mandate()}; {@code status} is
     * {@link #STATUS_ACTIVE} (a recorded consent is active on create); {@code createdAt}/{@code updatedAt}
     * are stamped to now; {@code revokedAt} is null.
     */
    public static Mandate create(String tenantId, String customerId, String paymentMethodId,
                                 boolean livemode, String type, String scenario,
                                 Map<String, Object> metadata) {
        Mandate m = new Mandate();
        m.id = PrefixedId.mandate();
        m.tenantId = tenantId;
        m.customerId = customerId;
        m.paymentMethodId = paymentMethodId;
        m.status = STATUS_ACTIVE;
        m.type = type;
        m.scenario = scenario;
        m.livemode = livemode;
        m.metadata = metadata != null ? new LinkedHashMap<>(metadata) : null;
        m.createdAt = Instant.now();
        m.updatedAt = m.createdAt;
        m.revokedAt = null;
        return m;
    }

    // -- Mutators --

    /**
     * REVOKE: deactivates the consent ({@code status -> INACTIVE}, stamps {@code revokedAt}, bumps
     * {@code updatedAt}). This is NOT a soft delete — the row stays retrievable (GET /{id} returns it with
     * status INACTIVE). An INACTIVE mandate fails the off-session charge gate (400 {@code invalid_mandate}).
     */
    public void revoke() {
        Instant now = Instant.now();
        this.status = STATUS_INACTIVE;
        this.revokedAt = now;
        this.updatedAt = now;
    }

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

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

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
}
