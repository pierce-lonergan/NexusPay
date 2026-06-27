package io.nexuspay.payment.application.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.event.EventTypes;
import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.payment.adapter.out.outbox.OutboxEvent;
import io.nexuspay.payment.adapter.out.outbox.OutboxEventRepository;
import io.nexuspay.payment.application.screening.ScreeningOriginService;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.RefundResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * INT-3: synthesizes the canonical webhook for a terminal MOCK ({@code sk_test_}) payment/refund by
 * writing the SAME {@link OutboxEvent} shape that {@code HyperSwitchWebhookController} writes for a real
 * PSP event. The existing {@code OutboxRelay -> WebhookDeliveryService -> WebhookEnvelopeSerializer}
 * pipeline then delivers a genuine test-mode webhook with ZERO new delivery code — the relay/delivery
 * path is untouched, this class only writes the outbox row.
 *
 * <p><b>Ordering contract (critical).</b> The {@code GatedPaymentGateway} invokes this AFTER it has
 * persisted the screening origin (trusted tenant) and the INT-1 V4030 metadata for the mock payment, so:
 * <ul>
 *   <li>the tenant resolved from {@link ScreeningOriginService} is the REAL tenant (not "default"), so
 *       {@code WebhookDeliveryService} fans out to that tenant's endpoints; and</li>
 *   <li>the INT-1 {@code userId}/{@code packId} round-trips (the delivery service looks it up by
 *       {@code aggregate_id} = {@code pay_test_*} under the same tenant), instead of racing the 1s relay
 *       poll and shipping {@code {}}.</li>
 * </ul>
 * Synthesis runs INSIDE the gateway call's transaction (the REST request), so the outbox row commits
 * with the business operation — the same transactional-outbox guarantee as the real controller.</p>
 *
 * <p>The synthesized event carries a server-set {@code metadata.livemode=false} marker (the test-mode
 * provenance for the delivered envelope's top-level {@code livemode} — see {@code
 * WebhookEnvelopeSerializer}) and {@code metadata.source="mock_sandbox"} so a test event is
 * distinguishable from a real {@code "hyperswitch_webhook"} one.</p>
 */
@Component
public class MockWebhookSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(MockWebhookSynthesizer.class);

    private static final String AGGREGATE_PAYMENT = EventTypes.AGGREGATE_PAYMENT; // "Payment"
    private static final String AGGREGATE_REFUND = EventTypes.AGGREGATE_REFUND;   // "Refund"
    private static final String DEFAULT_TENANT = "default";

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final ScreeningOriginService screeningOrigins;

    public MockWebhookSynthesizer(OutboxEventRepository outboxRepository,
                                  ObjectMapper objectMapper,
                                  ScreeningOriginService screeningOrigins) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.screeningOrigins = screeningOrigins;
    }

    /**
     * Synthesizes a terminal PAYMENT webhook (auto-capture create, capture, or void) for a mock payment.
     * No-op when the response/id is null. The internal event type maps via {@code WebhookEventTaxonomy}:
     * {@code PaymentCaptured -> payment.succeeded}, {@code PaymentVoided -> payment.canceled}.
     *
     * @param response    the mock payment response (carries the {@code pay_test_*} id, amount, currency)
     * @param tenantId    the TRUSTED tenant the gateway just recorded for this payment (origin store)
     * @param internalType the internal PascalCase event type ({@link EventTypes#PAYMENT_CAPTURED} / VOIDED)
     */
    public void onTerminal(PaymentResponse response, String tenantId, String internalType) {
        if (response == null || response.gatewayPaymentId() == null) {
            return;
        }
        String paymentId = response.gatewayPaymentId();
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("payment_id", paymentId);
        object.put("amount", response.amount());
        object.put("currency", response.currency());
        object.put("status", response.status());
        write(AGGREGATE_PAYMENT, paymentId, paymentId, internalType, object, tenantId);
    }

    /**
     * TEST-1: synthesizes a FAILED-PAYMENT webhook ({@code PaymentFailed -> payment.failed}) for a forced
     * test-mode decline. Same outbox shape + same {@link #write} delivery path as {@link #onTerminal}, but
     * the {@code data.object} carries {@code status="failed"} plus the mock's {@code error_code} /
     * {@code error_message}. Best-effort: a synthesis failure must never fail the already-completed mock op
     * (the {@code write(...)} swallows it, exactly like the success emitters). No-op when null.
     *
     * @param response    the FAILED mock payment response (carries {@code pay_test_*} id + error fields)
     * @param tenantId    the TRUSTED tenant the gateway recorded for this payment (origin store)
     * @param internalType the internal PascalCase event type ({@link EventTypes#PAYMENT_FAILED})
     */
    public void onTerminalFailure(PaymentResponse response, String tenantId, String internalType) {
        if (response == null || response.gatewayPaymentId() == null) {
            return;
        }
        String paymentId = response.gatewayPaymentId();
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("payment_id", paymentId);
        object.put("amount", response.amount());
        object.put("currency", response.currency());
        object.put("status", response.status());          // "failed"
        object.put("error_code", response.errorCode());
        object.put("error_message", response.errorMessage());
        write(AGGREGATE_PAYMENT, paymentId, paymentId, internalType, object, tenantId);
    }

    /**
     * Synthesizes a terminal REFUND webhook ({@code RefundCompleted -> payment.refunded}). The
     * {@code aggregate_id} is the originating PAYMENT id (mirrors the real controller's
     * {@code aggregate_id = payment_id}), so the INT-1 metadata lookup by payment id round-trips.
     *
     * @param refund      the mock refund response (carries the {@code re_test_*} id + payment id)
     * @param tenantId    the TRUSTED tenant resolved from the originating payment's origin row
     * @param internalType the internal PascalCase event type ({@link EventTypes#REFUND_COMPLETED})
     */
    public void onRefundTerminal(RefundResponse refund, String tenantId, String internalType) {
        if (refund == null || refund.paymentId() == null) {
            return;
        }
        String paymentId = refund.paymentId();
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("refund_id", refund.gatewayRefundId());
        object.put("payment_id", paymentId);          // serializer keeps payment_id on a refund object
        object.put("amount", refund.amount());
        object.put("currency", refund.currency());
        object.put("status", refund.status());
        write(AGGREGATE_REFUND, paymentId, paymentId, internalType, object, tenantId);
    }

    /**
     * TEST-1: synthesizes a FAILED-REFUND webhook ({@code RefundFailed -> payment.refund.failed}) for a
     * forced test-mode refund failure. Mirrors {@link #onRefundTerminal} (aggregate_id = originating PAYMENT
     * id, same {@link #write} delivery path) but the {@code data.object} carries {@code status="failed"} +
     * the mock's {@code error_code} / {@code error_message}. Best-effort + null-safe, like every emitter.
     *
     * @param refund      the FAILED mock refund response (carries {@code re_test_*} id + payment id + error)
     * @param tenantId    the TRUSTED tenant resolved from the originating payment's origin row
     * @param internalType the internal PascalCase event type ({@link EventTypes#REFUND_FAILED})
     */
    public void onRefundFailed(RefundResponse refund, String tenantId, String internalType) {
        if (refund == null || refund.paymentId() == null) {
            return;
        }
        String paymentId = refund.paymentId();
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("refund_id", refund.gatewayRefundId());
        object.put("payment_id", paymentId);
        object.put("amount", refund.amount());
        object.put("currency", refund.currency());
        object.put("status", refund.status());            // "failed"
        object.put("error_code", refund.errorCode());
        object.put("error_message", refund.errorMessage());
        write(AGGREGATE_REFUND, paymentId, paymentId, internalType, object, tenantId);
    }

    /**
     * Writes the outbox row in the same shape as {@code HyperSwitchWebhookController.handleWebhook}: a
     * JSON {@code {event_id, event_type, aggregate_type, aggregate_id, timestamp, version, metadata,
     * payload}} envelope as the outbox payload, plus the trusted tenant on the row. A serialization
     * failure is swallowed (a test webhook is best-effort and must never fail the authorized mock op).
     */
    private void write(String aggregateType, String aggregateId, String paymentId,
                       String internalType, Map<String, Object> object, String tenantId) {
        // Resolve the trusted tenant from the server-owned origin store (the SAME source the real webhook
        // controller uses) — never client metadata. The gateway already recorded the origin before calling
        // us, so this resolves the REAL tenant; "default" only if it is somehow absent (delivery gap, not
        // a leak — matches HyperSwitchWebhookController's fallback semantics).
        String resolved = tenantId != null && !tenantId.isBlank()
                ? tenantId
                : screeningOrigins.find(paymentId)
                        .map(ScreeningOriginService.Origin::tenantId)
                        .filter(t -> t != null && !t.isBlank())
                        .orElse(DEFAULT_TENANT);

        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", "mock_sandbox");                 // provenance (not "hyperswitch_webhook")
            metadata.put("original_event_id", PrefixedId.event());  // stable id, mirrors real path
            metadata.put("livemode", false);                        // INT-3 mode marker (test)

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("event_id", PrefixedId.event());
            envelope.put("event_type", internalType);
            envelope.put("aggregate_type", aggregateType);
            envelope.put("aggregate_id", aggregateId);
            envelope.put("timestamp", Instant.now().toString());
            envelope.put("version", 1);
            envelope.put("metadata", metadata);
            envelope.put("payload", object);

            String outboxPayload = objectMapper.writeValueAsString(envelope);
            outboxRepository.save(new OutboxEvent(
                    aggregateType, aggregateId, internalType, outboxPayload, resolved, 1));
            log.debug("Synthesized mock webhook: type={} aggregate={} tenant={}",
                    internalType, aggregateId, resolved);
        } catch (JsonProcessingException | RuntimeException e) {
            // Best-effort: a synthesis failure must NOT fail the already-completed mock payment op.
            log.warn("Failed to synthesize mock webhook for {} (type={}) — no test webhook emitted",
                    aggregateId, internalType);
        }
    }
}
