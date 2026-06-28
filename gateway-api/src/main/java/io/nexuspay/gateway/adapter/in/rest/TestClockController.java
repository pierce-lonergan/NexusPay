package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.common.exception.InvalidRequestException;
import io.nexuspay.common.tenant.CallerMode;
import io.nexuspay.common.tenant.CallerTenant;
import io.nexuspay.gateway.adapter.in.rest.dto.TestClockResponse;
import io.nexuspay.payment.application.service.clock.TestClockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

/**
 * GAP-078 (critique v3 F5): the TEST CLOCK control endpoint. Lets an integrator FREEZE the {@code created_at}
 * stamped on TEST-created payment/refund artifacts so timestamps + list ordering are deterministic in a test
 * run. MIRRORS {@link TestSandboxController} / {@link TestEventController} exactly: a hard
 * {@code CallerMode.isTest()} gate (404 no-oracle), tenant from the principal only, role + {@code test:write}
 * scope. Delegates to {@link TestClockService} across the existing gateway-api→payment-orchestration edge.
 *
 * <h3>★★★ HONEST SCOPE (read before extending). ★★★</h3>
 * <p>A frozen clock controls ONLY the {@code created_at} on the synthesized {@code PaymentResponse} /
 * {@code RefundResponse} built on the TEST rail — which the GAP-076 read-model projection inherits, so the
 * {@code GET /v1/payments}|{@code /v1/refunds} list ORDERING follows for free. It does NOT control mandate
 * expiry, idempotency-key TTL, webhook-delivery retry/backoff, api-key expiry, the projection
 * {@code updated_at}, the synthesized webhook envelope timestamp, or ANY live-rail behavior. This is the
 * deliberate "no fake fidelity" narrow design — see {@link TestClockService}.</p>
 *
 * <h3>Invariants (the review hunts these)</h3>
 * <ul>
 *   <li><b>UNREACHABLE by a live key.</b> Hard-gated on {@link CallerMode#isTest()} FIRST on EVERY handler —
 *       a LIVE key (or any non-test principal) gets 404 (no oracle the route exists, fail-closed). A live key
 *       can neither read, set, nor clear the clock; the clock is physically unable to affect a live charge.</li>
 *   <li><b>Per-tenant.</b> Every op runs under {@link CallerTenant#require()} (the authenticated principal's
 *       tenant, NEVER a body/header). Tenant A's clock can never affect tenant B's artifacts.</li>
 *   <li><b>Least-privilege scope.</b> {@code @scopeAuth.has('test:write')} — reuses the GAP-077 scope; NO new
 *       scope. The in-method {@code isTest()} gate runs after {@code @PreAuthorize}, so a live key still 404s.</li>
 *   <li><b>Validation.</b> An unparseable/missing {@code now} is rejected 400. A far-future/absurd instant is
 *       ALLOWED (test freedom).</li>
 * </ul>
 *
 * @since GAP-078
 */
@RestController
@RequestMapping("/v1/test/clock")
@Tag(name = "Test Helpers", description = "Test-mode-only control endpoints (reachable ONLY with an sk_test_ key)")
public class TestClockController {

    private static final Logger log = LoggerFactory.getLogger(TestClockController.class);

    private final TestClockService testClock;

    public TestClockController(TestClockService testClock) {
        this.testClock = testClock;
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('admin', 'operator') and @scopeAuth.has('test:write')")
    @Operation(summary = "Freeze the caller tenant's test clock (TEST mode only). Controls ONLY the created_at "
            + "on TEST-created payment/refund artifacts (and their list ordering) — NOT expiry/TTL/retry/"
            + "updated_at/live behavior.")
    public ResponseEntity<TestClockResponse> set(@RequestBody Map<String, Object> body) {
        // HARD GATE (mirrors TestSandboxController): a LIVE key must never reach this test-control endpoint.
        // 404 (not 403) so a live key gets no oracle the route exists. CallerMode is fail-closed.
        if (!CallerMode.isTest()) {
            return ResponseEntity.notFound().build();
        }
        // Tenant from the authenticated principal — NEVER a client header/body.
        String tenant = CallerTenant.require();

        Instant now = parseNow(body == null ? null : body.get("now"));
        testClock.set(tenant, now);

        log.info("Test clock SET for tenant={} -> {}", tenant, now);
        return ResponseEntity.ok(new TestClockResponse(now, true));
    }

    @DeleteMapping
    @PreAuthorize("hasAnyRole('admin', 'operator') and @scopeAuth.has('test:write')")
    @Operation(summary = "Clear the caller tenant's test clock -> reverts to real time (TEST mode only)")
    public ResponseEntity<TestClockResponse> clear() {
        if (!CallerMode.isTest()) {
            return ResponseEntity.notFound().build();
        }
        String tenant = CallerTenant.require();

        testClock.clear(tenant);

        log.info("Test clock CLEARED for tenant={} -> real time", tenant);
        // Reverted to real time: now = the live Instant.now(), frozen = false.
        return ResponseEntity.ok(new TestClockResponse(Instant.now(), false));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('admin', 'operator') and @scopeAuth.has('test:write')")
    @Operation(summary = "Read the caller tenant's test-clock state (TEST mode only)")
    public ResponseEntity<TestClockResponse> get() {
        if (!CallerMode.isTest()) {
            return ResponseEntity.notFound().build();
        }
        String tenant = CallerTenant.require();

        Optional<Instant> frozen = testClock.get(tenant);
        // now = the frozen instant if set, else real time; frozen = whether a row exists.
        return ResponseEntity.ok(new TestClockResponse(
                frozen.orElseGet(Instant::now), frozen.isPresent()));
    }

    /**
     * Parses the request body's {@code now} into an {@link Instant}. An unparseable/missing/null value is a
     * 400 ({@link InvalidRequestException}); a far-future/absurd-but-valid ISO-8601 instant is accepted.
     */
    private static Instant parseNow(Object raw) {
        if (raw == null) {
            throw new InvalidRequestException("now is required (an ISO-8601 instant)", "validation_error");
        }
        try {
            return Instant.parse(raw.toString());
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException(
                    "now must be an ISO-8601 instant (e.g. 2026-01-01T00:00:00Z): " + raw, "validation_error");
        }
    }
}
