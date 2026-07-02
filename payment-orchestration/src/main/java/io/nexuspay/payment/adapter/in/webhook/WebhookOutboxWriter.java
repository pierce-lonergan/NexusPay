package io.nexuspay.payment.adapter.in.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.payment.adapter.out.outbox.OutboxEvent;
import io.nexuspay.payment.adapter.out.outbox.OutboxEventRepository;
import io.nexuspay.payment.application.screening.ScreeningOriginService;
import io.nexuspay.payment.domain.event.PaymentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;

/**
 * GAP-015: single source of truth for turning a persisted HyperSwitch webhook envelope into the ONE
 * canonical {@code event_outbox} row, including the SEC-09 trusted-tenant stamp.
 *
 * <p>Used by the OPERATOR REPROCESS path ({@link WebhookReprocessController}) so a re-driven webhook
 * produces a row that is IDENTICAL — same {@code mapHyperSwitchEventType}, same aggregateType/aggregateId
 * derivation, same {@code Map}-shaped payload, same tenant resolution — to what the live handler
 * ({@link HyperSwitchWebhookController#processClaimedWebhook}) would have written. The mapping logic is
 * deliberately kept byte-for-byte aligned with the live handler; changing one MUST change the other.</p>
 *
 * <p><b>TENANT (SEC-09 / B-009):</b> resolved from the SERVER-OWNED origin store
 * ({@code ScreeningOriginService.find(paymentId)}), NEVER from client input and NEVER from the persisted
 * {@code inbound_webhooks.tenant_id} column (which the live handler never stamps — it defaults to
 * 'default'). When no origin exists the row falls back to 'default' with the SAME warn + metric the live
 * handler emits, so the reprocessed row routes exactly as the original would have (a delivery gap, never
 * a cross-tenant leak).</p>
 *
 * <p><b>LIVEMODE:</b> {@code event_outbox} has no livemode column (V1301 + V2002 add only
 * routing_key/event_version), so the HyperSwitch outbox row is not stamped with livemode at all — the
 * reprocess path reproduces the identical row and does not thread livemode onto it.</p>
 */
@org.springframework.stereotype.Component
public class WebhookOutboxWriter {

    private static final Logger log = LoggerFactory.getLogger(WebhookOutboxWriter.class);

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final ScreeningOriginService screeningOrigins;

    /**
     * Mirrors {@code HyperSwitchWebhookController.webhookDefaultTenantStamped}: counts reprocessed webhook
     * events for a real payment id whose owning tenant could NOT be resolved, so they were stamped
     * {@code "default"} and will be dropped by the tenant-scoped consumer. Alert on a non-zero rate.
     */
    private final Counter defaultTenantStamped;

    public WebhookOutboxWriter(OutboxEventRepository outboxRepository,
                               ObjectMapper objectMapper,
                               ScreeningOriginService screeningOrigins,
                               MeterRegistry meterRegistry) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.screeningOrigins = screeningOrigins;
        this.defaultTenantStamped = Counter.builder("nexuspay.webhook.reprocess.default_tenant_stamped")
                .description("Reprocessed webhook events stamped tenant=default because the payment's "
                        + "origin tenant could not be resolved (NOT delivered to the owning tenant)")
                .register(meterRegistry);
    }

    /**
     * Parses the persisted raw HyperSwitch envelope and writes the canonical outbox row inside the
     * CALLER's transaction (so a re-insert failure rolls the caller's status flip back). Throws
     * {@link JsonProcessingException} on a serialization failure so the reprocess tx rolls back and the
     * inbound row stays FAILED (re-drivable). Returns the resolved tenant for audit logging.
     */
    String writeOutboxRow(String rawPayload) throws JsonProcessingException {
        JsonNode payload = objectMapper.readTree(rawPayload);

        String eventId = extractField(payload, "event_id", PrefixedId.event());
        String eventType = extractField(payload, "event_type", "unknown");
        String paymentId = extractNestedField(payload, "content", "object", "payment_id");

        String nexusEventType = mapHyperSwitchEventType(eventType);
        String aggregateType = nexusEventType.startsWith("Refund") ? "Refund" : "Payment";
        String aggregateId = paymentId != null ? paymentId : eventId;

        String outboxPayload = objectMapper.writeValueAsString(Map.of(
                "event_id", PrefixedId.event(),
                "event_type", nexusEventType,
                "aggregate_type", aggregateType,
                "aggregate_id", aggregateId,
                "timestamp", Instant.now().toString(),
                "version", 1,
                "metadata", Map.of(
                        "source", "hyperswitch_webhook",
                        "original_event_id", eventId
                ),
                "payload", payload.path("content").path("object")
        ));

        // SEC-09 (B-009): stamp the TRUSTED origin tenant recalled from the server-owned store by gateway
        // payment id (never client metadata, never the persisted inbound tenant_id column). Absent origin
        // -> "default" (routes to no real tenant = delivery gap, never a cross-tenant leak) + warn/metric.
        boolean hasPaymentId = paymentId != null;
        String resolvedTenant = hasPaymentId
                ? screeningOrigins.find(paymentId)
                    .map(ScreeningOriginService.Origin::tenantId)
                    .filter(t -> t != null && !t.isBlank())
                    .orElse(null)
                : null;
        String eventTenant = resolvedTenant != null ? resolvedTenant : "default";

        if (hasPaymentId && resolvedTenant == null) {
            log.warn("SEC-09 reprocess webhook tenant unresolved for payment_id={} event_type={} — stamping "
                    + "\"default\"; this event will NOT be delivered to the owning tenant's endpoints",
                    paymentId, nexusEventType);
            defaultTenantStamped.increment();
        }

        outboxRepository.save(new OutboxEvent(
                aggregateType, aggregateId, nexusEventType, outboxPayload, eventTenant, 1));

        return eventTenant;
    }

    /**
     * Maps HyperSwitch event types to NexusPay event types. Kept identical to
     * {@code HyperSwitchWebhookController.mapHyperSwitchEventType} so a reprocessed row matches the
     * original.
     */
    private String mapHyperSwitchEventType(String hsEventType) {
        if (hsEventType == null) return "Unknown";
        return switch (hsEventType.toLowerCase()) {
            case "payment_succeeded" -> PaymentEvent.PAYMENT_CAPTURED;
            case "payment_failed" -> PaymentEvent.PAYMENT_FAILED;
            case "payment_processing" -> PaymentEvent.PAYMENT_AUTHORIZED;
            case "payment_cancelled" -> PaymentEvent.PAYMENT_VOIDED;
            case "refund_succeeded" -> PaymentEvent.REFUND_COMPLETED;
            case "refund_failed" -> PaymentEvent.REFUND_FAILED;
            default -> hsEventType;
        };
    }

    private String extractField(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? defaultValue : value.asText();
    }

    private String extractNestedField(JsonNode node, String... path) {
        JsonNode current = node;
        for (String field : path) {
            current = current.path(field);
            if (current.isMissingNode()) return null;
        }
        return current.isNull() ? null : current.asText();
    }
}
