package io.nexuspay.dispute.adapter.in.rest;

import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.common.tenant.CallerMode;
import io.nexuspay.common.tenant.CallerTenant;
import io.nexuspay.dispute.application.service.DisputeLifecycleService;
import io.nexuspay.dispute.domain.Dispute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * TEST-2: the TEST-mode dispute SIMULATOR — the first endpoint of the {@code /v1/test/*} control
 * surface.
 *
 * <h3>What it does</h3>
 * <p>{@code POST /v1/test/disputes} opens a dispute (chargeback) on a TEST payment under the CALLER's
 * tenant so an integrator can drive their dispute-webhook handling end-to-end locally: the open emits a
 * {@code dispute.created} (and {@code dispute.funds_withdrawn}) webhook through the SAME signed delivery
 * pipeline a real chargeback would, stamped {@code livemode=false}. Body:
 * {@code {payment_id, amount?, currency?, reason?}}.</p>
 *
 * <h3>Invariants (the review hunts these)</h3>
 * <ul>
 *   <li><b>UNREACHABLE by a live key.</b> Hard-gated on {@link CallerMode#isTest()} (the principal's
 *       server-derived {@code live() == false}). A LIVE key (or any non-test principal) gets a 404 — no
 *       production oracle, and no way to fabricate a real-looking dispute against a live merchant.</li>
 *   <li><b>Tenant-scoped.</b> The dispute is opened under {@link CallerTenant#require()} (the
 *       authenticated principal's tenant, NEVER a client {@code X-Tenant-Id} header). A caller can only
 *       ever open a dispute under THEIR OWN tenant, so the emitted events fan out to their own merchant
 *       webhooks only.</li>
 *   <li><b>Test payment only (by PREFIX).</b> The {@code payment_id} must be a {@code pay_test_*} id —
 *       a {@code pay_live_*} / arbitrary id is rejected 400, so the simulator can never be pointed at a
 *       real (live) payment. The id is validated by PREFIX only and treated as an opaque test reference;
 *       the dispute module has no payments port, so the id is NOT resolved against the payments service
 *       and need not correspond to an existing test payment. This is safe because the dispute is opened
 *       under the caller's own tenant (the events fan out to the caller's own endpoints only) and the
 *       chargeback reserve posts under that tenant + the dispute id (never the {@code payment_id}), so an
 *       unknown id yields no cross-tenant leak and no payment-existence oracle.</li>
 * </ul>
 *
 * <p>All {@code /v1/test/**} routes sit behind the global {@code anyRequest().authenticated()} gate, so
 * the endpoint already requires authentication; the {@code isTest()} gate narrows that to TEST keys.</p>
 *
 * @since TEST-2
 */
@RestController
@RequestMapping("/v1/test/disputes")
public class DisputeTestController {

    private static final Logger log = LoggerFactory.getLogger(DisputeTestController.class);

    /** A simulated dispute uses a test reason code when the caller does not supply one. */
    private static final String DEFAULT_REASON = "fraudulent";
    private static final String DEFAULT_CURRENCY = "USD";
    private static final long DEFAULT_AMOUNT = 1000L; // minor units

    private final DisputeLifecycleService lifecycleService;

    public DisputeTestController(DisputeLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @PostMapping
    public ResponseEntity<?> simulateDispute(@RequestBody Map<String, Object> body) {
        // HARD GATE: a LIVE key must never reach this test-control endpoint. 404 (not 403) so a live key
        // gets no oracle that the route even exists. CallerMode is fail-closed: a non-LiveModePrincipal
        // thread reads as live -> also 404.
        if (!CallerMode.isTest()) {
            return ResponseEntity.notFound().build();
        }

        // Tenant resolved from the authenticated principal — never a client header. The dispute is opened
        // UNDER THIS TENANT, so a caller can only ever simulate a dispute on their own tenant.
        String tenant = CallerTenant.require();

        String paymentId = asString(body.get("payment_id"));
        if (paymentId == null || paymentId.isBlank()) {
            return badRequest("payment_id is required");
        }
        // Test payments only: a pay_test_* id. Reject anything else so the simulator can never be aimed
        // at a real (pay_live_*) payment.
        if (!paymentId.startsWith("pay_test_")) {
            return badRequest("payment_id must be a test-mode payment (pay_test_*)");
        }

        long amount = asLong(body.get("amount"), DEFAULT_AMOUNT);
        String currency = orDefault(asString(body.get("currency")), DEFAULT_CURRENCY);
        String reason = orDefault(asString(body.get("reason")), DEFAULT_REASON);

        // A simulated dispute gets a synthetic external id so it is idempotent + traceable as a TEST
        // dispute (and distinct from any real network dispute).
        String externalDisputeId = "test_dp_" + PrefixedId.generate("");

        // livemode=false -> the emitted dispute.* webhooks are stamped TEST.
        Dispute dispute = lifecycleService.openDispute(
                tenant, paymentId, externalDisputeId,
                reason, "Simulated test dispute",
                amount, currency, "test",
                null, false);

        log.info("Test dispute simulated: id={}, payment={}, tenant={}",
                dispute.getId(), paymentId, tenant);

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(dispute));
    }

    private static Map<String, Object> toResponse(Dispute d) {
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("id", d.getId());
        r.put("payment_id", d.getPaymentId());
        r.put("external_dispute_id", d.getExternalDisputeId());
        r.put("amount", d.getAmount());
        r.put("currency", d.getCurrency());
        r.put("status", d.getStatus().name());
        r.put("reason_code", d.getReasonCode());
        r.put("network", d.getNetwork());
        r.put("created_at", d.getCreatedAt().toString());
        r.put("livemode", false);
        return r;
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", Map.of(
                "type", "validation_error", "message", message)));
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static long asLong(Object v, long dflt) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return dflt;
    }

    private static String orDefault(String v, String dflt) {
        return (v == null || v.isBlank()) ? dflt : v;
    }
}
