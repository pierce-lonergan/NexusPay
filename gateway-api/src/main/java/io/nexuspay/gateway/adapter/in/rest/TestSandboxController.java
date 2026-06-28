package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.common.tenant.CallerMode;
import io.nexuspay.common.tenant.CallerTenant;
import io.nexuspay.gateway.adapter.in.rest.dto.SandboxResetResponse;
import io.nexuspay.payment.application.service.sandbox.SandboxResetService;
import io.nexuspay.payment.application.service.sandbox.SandboxResetSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GAP-077 (critique v3 F4): the TEST-mode SANDBOX RESET — hard-wipes the CALLER's tenant's TEST data so an
 * integrator can start a clean test run between runs. MIRRORS {@link TestEventController} exactly: a hard
 * {@code CallerMode.isTest()} gate (404 no-oracle), tenant from the principal only, role + {@code test:write}
 * scope. Delegates the scoped deletes to {@link SandboxResetService} across the existing
 * gateway-api→payment-orchestration edge.
 *
 * <h3>Invariants (the review hunts these)</h3>
 * <ul>
 *   <li><b>UNREACHABLE by a live key.</b> Hard-gated on {@link CallerMode#isTest()} FIRST — a LIVE key (or
 *       any non-test principal) gets a 404 (no oracle the route exists, fail-closed). The service never runs
 *       for a live key, so LIVE data can never be deleted.</li>
 *   <li><b>Tenant-scoped.</b> The reset runs under {@link CallerTenant#require()} (the authenticated
 *       principal's tenant, NEVER a body/header); every DELETE inside the service carries
 *       {@code tenant_id = <caller> AND livemode = false}, so another tenant's data can never be touched.</li>
 *   <li><b>Least-privilege scope.</b> {@code @scopeAuth.has('test:write')} — a key can be granted
 *       sandbox-control without {@code payments:write}/{@code webhooks:write}. The in-method
 *       {@code isTest()} gate runs after {@code @PreAuthorize}, so a live key still 404s (the gate wins).</li>
 * </ul>
 *
 * @since GAP-077
 */
@RestController
@RequestMapping("/v1/test/sandbox")
@Tag(name = "Test Helpers", description = "Test-mode-only control endpoints (reachable ONLY with an sk_test_ key)")
public class TestSandboxController {

    private static final Logger log = LoggerFactory.getLogger(TestSandboxController.class);

    private final SandboxResetService sandboxResetService;

    public TestSandboxController(SandboxResetService sandboxResetService) {
        this.sandboxResetService = sandboxResetService;
    }

    @PostMapping("/reset")
    @PreAuthorize("hasAnyRole('admin', 'operator') and @scopeAuth.has('test:write')")
    @Operation(summary = "Reset (hard-wipe) the caller tenant's TEST data (TEST mode only)")
    public ResponseEntity<SandboxResetResponse> reset() {
        // HARD GATE (mirrors TestEventController): a LIVE key must never reach this DESTRUCTIVE endpoint.
        // 404 (not 403) so a live key gets no oracle the route exists. CallerMode is fail-closed: a
        // non-LiveModePrincipal thread reads as live -> also 404.
        if (!CallerMode.isTest()) {
            return ResponseEntity.notFound().build();
        }

        // Tenant from the authenticated principal — NEVER a client header/body. Every delete in the service
        // is scoped to this tenant AND livemode=false.
        String tenant = CallerTenant.require();

        SandboxResetSummary summary = sandboxResetService.reset(tenant);

        log.info("Sandbox reset completed for tenant={}: {}", tenant, summary);

        return ResponseEntity.ok(new SandboxResetResponse(
                summary.payments(),
                summary.refunds(),
                summary.customers(),
                summary.paymentMethods(),
                summary.mandates()));
    }
}
