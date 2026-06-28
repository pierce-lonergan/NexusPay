package io.nexuspay.payment.application.service.clock;

import io.nexuspay.payment.application.port.out.TestClockRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * GAP-078 (critique v3 F5): the per-tenant TEST CLOCK. Lets an integrator FREEZE the {@code createdAt}
 * stamped on TEST-created payment/refund artifacts so that timestamps + list ordering are deterministic in
 * a test run (e.g. set {@code now = 2026-01-01T00:00:00Z}, create payments, assert their {@code created_at}).
 *
 * <h3>★★★ HONEST NON-SCOPE (the deliberate narrow design — read this before extending). ★★★</h3>
 * <p>This clock controls ONLY ONE thing: the {@code createdAt} field on the synthesized
 * {@code PaymentResponse}/{@code RefundResponse} built by the mock on the TEST rail — which the GAP-076
 * read-model projection then INHERITS (the projection takes {@code created_at} FROM the response), so the
 * {@code GET /v1/payments} | {@code GET /v1/refunds} list ORDERING (created_at DESC) follows the frozen
 * instant for free.</p>
 *
 * <p>That single frozen {@code createdAt} is consistent across EVERY read of the same test artifact: the
 * create response ({@code POST}), the LIST ({@code GET /v1/payments} | {@code /v1/refunds}, via the GAP-076
 * projection), AND the SINGLE-RETRIEVE ({@code GET /v1/payments/{id}} | {@code /v1/refunds/{id}}, served from
 * the mock store). The gateway re-stamps both the returned response and the mock STORE (via {@code
 * MockPaymentGatewayPort.restampCreatedAt}), so single-retrieve never silently diverges to real time.</p>
 *
 * <p>It does <b>NOT</b> control, and deliberately does not fake control over:</p>
 * <ul>
 *   <li>mandate expiry / validation (V4040);</li>
 *   <li>idempotency-key TTL;</li>
 *   <li>webhook-delivery retry / backoff timing;</li>
 *   <li>api-key expiry;</li>
 *   <li>the projection's {@code updated_at} (always {@code Instant.now()} in the adapter, by design);</li>
 *   <li>the {@code MockWebhookSynthesizer} outbox-envelope {@code timestamp} (a serialization detail
 *       resolved at outbox-write depth where the tenant is not cleanly in hand — left at real time);</li>
 *   <li>ANY live-rail behavior or any LIVE charge's timestamp.</li>
 * </ul>
 *
 * <p>This mirrors the "no fake fidelity" principle: building the narrow honest thing beats a global
 * {@code java.time.Clock} retrofit that would MISLEAD by appearing to control time where it does not.</p>
 *
 * <h3>LIVE ISOLATION (the safety invariant).</h3>
 * <p>{@link #nowFor(String)} is consulted ONLY by {@code GatedPaymentGateway}'s {@code routeToMock}
 * branches (the mock/test rail). A LIVE charge goes through the real HyperSwitch delegate, which stamps its
 * own time and never consults this service — so the clock is PHYSICALLY UNABLE to alter a live timestamp.</p>
 *
 * <h3>TENANT ISOLATION.</h3>
 * <p>Every method is keyed by the caller's principal tenant via a tenant-scoped repository (no unscoped
 * finder). Tenant A's clock never affects tenant B's artifacts.</p>
 *
 * <h3>Cache-free.</h3>
 * <p>{@link #nowFor(String)} does ONE primary-key read per create. That is cheap, and being read-per-create
 * means there is NO staleness window if a clock is set/cleared mid-run (always correct).</p>
 *
 * @since GAP-078
 */
@Service
public class TestClockService {

    private final TestClockRepository repository;

    public TestClockService(TestClockRepository repository) {
        this.repository = repository;
    }

    /**
     * The instant to stamp on a TEST-created artifact for {@code tenantId}: the tenant's frozen instant if a
     * clock is set, else the REAL {@code Instant.now()}. NEVER throws on a read miss. A null/blank tenant
     * (the no-trusted-tenant create path) falls back to real time, so that path is byte-identical to today.
     *
     * <p>Consulted ONLY on the mock/test rail (see class javadoc) — never for a live charge.</p>
     */
    public Instant nowFor(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Instant.now();
        }
        return repository.findByTenantId(tenantId).orElseGet(Instant::now);
    }

    /**
     * Freezes the tenant's clock at {@code fixedAt}. A far-future / absurd instant is ALLOWED (test freedom);
     * only a null is rejected here (the controller maps an unparseable/missing value to 400 before calling).
     *
     * @throws IllegalArgumentException if {@code fixedAt} is null (defensive — the controller validates first)
     */
    public void set(String tenantId, Instant fixedAt) {
        if (fixedAt == null) {
            throw new IllegalArgumentException("fixedAt must not be null");
        }
        repository.upsert(tenantId, fixedAt);
    }

    /** Clears the tenant's clock -> {@link #nowFor} reverts to real time for that tenant. */
    public void clear(String tenantId) {
        repository.deleteByTenantId(tenantId);
    }

    /** The tenant's frozen instant if set, else empty (drives the {@code GET /v1/test/clock} frozen flag). */
    public Optional<Instant> get(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        return repository.findByTenantId(tenantId);
    }
}
