package io.nexuspay.payment.application.port.out;

import java.time.Instant;
import java.util.Optional;

/**
 * GAP-078 (critique v3 F5): output port for the per-tenant TEST CLOCK ({@code test_clocks}, V4042).
 *
 * <p>The clock stores ONE frozen instant per tenant; a row's ABSENCE means "real time". It is read ONLY on
 * the mock/test rail (by {@code TestClockService.nowFor}, consulted exclusively inside
 * {@code GatedPaymentGateway}'s {@code routeToMock} branches) to stamp the {@code createdAt} on
 * TEST-created payment/refund artifacts. It controls NOTHING else — see {@code TestClockService} for the
 * authoritative non-scope.</p>
 *
 * <p>TENANT-SCOPED ONLY. Every operation is keyed by the caller's principal tenant; there is deliberately
 * NO unscoped finder (mirrors {@link MandateRepository} / {@link PaymentProjectionRepository}). A tenant
 * can read/set/clear ONLY its own clock, so tenant A's clock can never affect tenant B's artifacts.</p>
 *
 * @since GAP-078
 */
public interface TestClockRepository {

    /**
     * The tenant's frozen instant, or empty when no clock is set (= real time). The ONLY read finder; it is
     * tenant-scoped (the PK lookup IS the tenant scope), so a foreign tenant's clock is never returned.
     */
    Optional<Instant> findByTenantId(String tenantId);

    /**
     * Sets (inserts or replaces) the tenant's frozen instant; bumps {@code updated_at}. Far-future/absurd
     * instants are allowed (test freedom) — validation of null happens in the service/controller layer.
     */
    void upsert(String tenantId, Instant fixedAt);

    /** Removes the tenant's clock row -> {@link #findByTenantId} becomes empty -> reverts to real time. */
    void deleteByTenantId(String tenantId);
}
