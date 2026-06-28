package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.common.event.EventTypes;
import io.nexuspay.common.event.WebhookEventTaxonomy;
import io.nexuspay.common.exception.InvalidRequestException;
import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.common.tenant.CallerMode;
import io.nexuspay.common.tenant.CallerTenant;
import io.nexuspay.gateway.adapter.in.rest.dto.TestEventResponse;
import io.nexuspay.gateway.adapter.out.webhook.TestEventOutboxAdapter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TEST-4a (D1): the TEST-mode webhook-event TRIGGER — synthesize + deliver a canonical webhook of a chosen
 * type to the caller's OWN tenant's endpoints, so an integrator can exercise their webhook receiver WITHOUT
 * driving a real payment.
 *
 * <h3>What it does</h3>
 * <p>{@code POST /v1/test/events} with {@code {type (dotted canonical, required), id? (optional aggregate
 * id), data? (object overlay)}} writes ONE row to the shared {@code event_outbox} under the CALLER's tenant
 * with {@code __livemode=false}; the existing {@code OutboxRelay → WebhookDeliveryService} pipeline signs +
 * delivers a {@code livemode:false} webhook to that tenant's enabled endpoints. No real money moves.</p>
 *
 * <h3>Invariants (the review hunts these)</h3>
 * <ul>
 *   <li><b>UNREACHABLE by a live key.</b> Hard-gated on {@link CallerMode#isTest()} FIRST — a LIVE key (or
 *       any non-test principal) gets a 404 (no oracle the route exists, fail-closed). A test key cannot
 *       synthesize a live event.</li>
 *   <li><b>Tenant-scoped.</b> The event is written under {@link CallerTenant#require()} (the authenticated
 *       principal's tenant, NEVER a body/header). {@code WebhookDeliveryService} fans out ONLY to that
 *       tenant's endpoints, so a test key can target only its own endpoints.</li>
 *   <li><b>Fail-closed type.</b> An unknown / non-canonical / {@code "*"} type is rejected 400.</li>
 *   <li><b>Opaque id (L-070).</b> The optional {@code id} is a prefix-shaped TEST reference; it is NOT
 *       resolved against any aggregate (gateway-api has no payments lookup here) — exactly like
 *       DisputeTestController's {@code payment_id}.</li>
 * </ul>
 *
 * <p>Delivery gap (by design): a synthesized event only delivers if the caller tenant has an ENABLED
 * endpoint subscribed to that dotted type (or {@code "*"}). With no matching endpoint the event silently
 * no-ops — register/subscribe an endpoint first.</p>
 *
 * @since TEST-4a
 */
@RestController
@RequestMapping("/v1/test/events")
@Tag(name = "Test Helpers", description = "Test-mode-only control endpoints (reachable ONLY with an sk_test_ key)")
public class TestEventController {

    private static final Logger log = LoggerFactory.getLogger(TestEventController.class);

    private final TestEventOutboxAdapter outbox;

    public TestEventController(TestEventOutboxAdapter outbox) {
        this.outbox = outbox;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('admin', 'operator') and @scopeAuth.has('webhooks:write')")
    @Operation(summary = "Trigger a synthetic webhook event (TEST mode only)")
    public ResponseEntity<TestEventResponse> trigger(@RequestBody Map<String, Object> body) {
        // HARD GATE (mirrors DisputeTestController): a LIVE key must never reach this test-control endpoint.
        // 404 (not 403) so a live key gets no oracle the route exists. CallerMode is fail-closed: a
        // non-LiveModePrincipal thread reads as live -> also 404.
        if (!CallerMode.isTest()) {
            return ResponseEntity.notFound().build();
        }

        // Tenant from the authenticated principal — NEVER a client header/body. The event is written UNDER
        // this tenant, so the synthesized webhook fans out to the caller's own endpoints only.
        String tenant = CallerTenant.require();

        String dottedType = asString(body.get("type"));
        if (dottedType == null || dottedType.isBlank()) {
            throw new InvalidRequestException("type is required", "validation_error");
        }
        // Fail-closed: reject anything not in the canonical set ('*' is not a concrete event -> rejected).
        if (!WebhookEventTaxonomy.CANONICAL.contains(dottedType)) {
            throw new InvalidRequestException(
                    "Unknown or non-deliverable event type: " + dottedType, "validation_error");
        }

        // Reverse-map dotted -> internal PascalCase + aggregate type (single-sourced in WebhookEventTaxonomy).
        String internalType = WebhookEventTaxonomy.fromDotted(dottedType);
        String aggregateType = WebhookEventTaxonomy.aggregateTypeFor(internalType);

        // Aggregate id: caller-supplied (opaque test reference) or a generated test-prefixed id with the
        // aggregate-correct prefix. Payment/Refund objects -> pay_test_*; Dispute -> dp_test_*.
        String aggregateId = asString(body.get("id"));
        if (aggregateId == null || aggregateId.isBlank()) {
            aggregateId = generateTestId(aggregateType);
        }

        // Build a representative data.object (id + a few type-appropriate fields), then overlay the
        // caller-supplied `data` map (so an integrator can shape amount/currency/etc).
        Map<String, Object> object = buildObject(aggregateType, aggregateId);
        Object overlay = body.get("data");
        if (overlay instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) {
                    object.put(e.getKey().toString(), e.getValue());
                }
            }
        }

        // Synthesize the outbox row under the caller tenant with livemode=false. The relay + delivery
        // pipeline then signs + delivers the webhook to the caller tenant's endpoints only.
        String eventId = outbox.synthesize(tenant, internalType, aggregateType, aggregateId, object, false);

        log.info("Test event triggered: type={}, internal={}, aggregate={}:{}, tenant={}",
                dottedType, internalType, aggregateType, aggregateId, tenant);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new TestEventResponse(eventId, dottedType, false, object));
    }

    /** A generated test aggregate id with the aggregate-correct prefix (Dispute -> dp_test_, else pay_test_). */
    private static String generateTestId(String aggregateType) {
        String prefix = EventTypes.AGGREGATE_DISPUTE.equals(aggregateType) ? "dp_test_" : "pay_test_";
        return PrefixedId.generate(prefix);
    }

    /**
     * Builds a representative {@code data.object} for the aggregate type. The keys mirror what the real
     * serializer would carry (an {@code id} discriminator + a few type-appropriate fields) so a delivered
     * test webhook looks shaped like a real one; the caller's {@code data} overlay can replace any of them.
     */
    private static Map<String, Object> buildObject(String aggregateType, String aggregateId) {
        Map<String, Object> object = new LinkedHashMap<>();
        if (EventTypes.AGGREGATE_DISPUTE.equals(aggregateType)) {
            object.put("dispute_id", aggregateId);
            object.put("amount", 1000L);
            object.put("currency", "USD");
            object.put("reason", "fraudulent");
            object.put("status", "needs_response");
        } else if (EventTypes.AGGREGATE_REFUND.equals(aggregateType)) {
            object.put("refund_id", aggregateId);
            object.put("amount", 1000L);
            object.put("currency", "USD");
            object.put("status", "succeeded");
        } else {
            object.put("payment_id", aggregateId);
            object.put("amount", 1000L);
            object.put("currency", "USD");
            object.put("status", "succeeded");
        }
        return object;
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }
}
