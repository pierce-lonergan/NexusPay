package io.nexuspay.gateway.adapter.out.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * INT-1: pure transform from the internal outbox payload (the Kafka record value) to the canonical,
 * Stripe-shaped public webhook envelope delivered to merchants. Stateless and side-effect free so it is
 * unit-testable in isolation; the only collaborator is the shared {@link ObjectMapper}.
 *
 * <p>The internal Kafka/outbox bytes are NEVER mutated — this is applied at SEND time only, and the HMAC
 * signature is computed over the EXACT String this returns (so the bytes the merchant verifies are the
 * bytes posted). Top-level key order is fixed ({@code id, type, created, api_version, data}) for
 * deterministic serialization.</p>
 *
 * <pre>{@code
 * {
 *   "id":          stable event id (metadata.original_event_id → event_id → aggregate_id),
 *   "type":        dotted canonical type (caller-provided, from WebhookEventTaxonomy),
 *   "created":     epoch SECONDS (UTC) derived from outbox "timestamp",
 *   "api_version": "2026-06-16",
 *   "data": { "object": normalized payment/refund object, "metadata": merchant correlation map ({} if none) }
 * }
 * }</pre>
 */
public class WebhookEnvelopeSerializer {

    /** The contract version stamped on every envelope. Constant by design. */
    public static final String API_VERSION = "2026-06-16";

    /**
     * Keys defensively stripped from {@code data.object} (belt-and-suspenders — the PSP lifecycle
     * {@code content.object} for these events does not carry raw PAN, but we never want a card subtree to
     * leak into a delivered envelope). Matched case-insensitively.
     */
    private static final java.util.Set<String> FORBIDDEN_OBJECT_KEYS = java.util.Set.of(
            "payment_method_data", "card", "payment_method");

    private final ObjectMapper objectMapper;

    public WebhookEnvelopeSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Builds and serializes the canonical envelope.
     *
     * @param outbox     the parsed internal outbox payload (Kafka record value)
     * @param dottedType the dotted canonical event type (already translated + validated as deliverable)
     * @param metadata   the server-owned merchant correlation map ({@code {}} when none — never null)
     * @return the exact JSON string to POST (and to sign)
     * @throws JsonProcessingException if serialization fails (caller treats as a delivery failure)
     */
    public String serialize(JsonNode outbox, String dottedType, Map<String, Object> metadata)
            throws JsonProcessingException {
        // LinkedHashMap preserves the fixed top-level key order required for deterministic bytes.
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", stableId(outbox));
        envelope.put("type", dottedType);
        envelope.put("created", createdEpochSeconds(outbox));
        envelope.put("api_version", API_VERSION);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("object", normalizeObject(outbox));
        data.put("metadata", metadata != null ? metadata : Map.of());
        envelope.put("data", data);

        return objectMapper.writeValueAsString(envelope);
    }

    /**
     * Stable event id: {@code metadata.original_event_id} (PSP-origin, stable across redelivery) →
     * {@code event_id} (stored outbox id) → {@code aggregate_id} (last resort). NEVER minted per send.
     */
    static String stableId(JsonNode outbox) {
        String original = text(outbox.path("metadata").path("original_event_id"));
        if (original != null) return original;
        String eventId = text(outbox.path("event_id"));
        if (eventId != null) return eventId;
        String aggregateId = text(outbox.path("aggregate_id"));
        return aggregateId != null ? aggregateId : "";
    }

    /**
     * {@code created} = epoch SECONDS of the outbox {@code timestamp} (ISO-8601). Falls back to delivery
     * time (now) when the timestamp is missing/unparseable — never fails the delivery.
     */
    static long createdEpochSeconds(JsonNode outbox) {
        String ts = text(outbox.path("timestamp"));
        if (ts != null) {
            try {
                return Instant.parse(ts).getEpochSecond();
            } catch (DateTimeParseException ignored) {
                // fall through to delivery-time fallback
            }
        }
        return Instant.now().getEpochSecond();
    }

    /**
     * Normalizes the outbox {@code payload} (the PSP {@code content.object}) into {@code data.object}:
     * copy through non-destructively, overlay an {@code id}/{@code object} discriminator, and strip any
     * card/PAN subtree. {@code object} is {@code "refund"} when {@code aggregate_type == "Refund"}, else
     * {@code "payment"}; {@code id} is {@code refund_id} (refund) / {@code payment_id} (payment), falling
     * back to the outbox {@code aggregate_id}.
     */
    ObjectNode normalizeObject(JsonNode outbox) {
        boolean isRefund = "Refund".equals(text(outbox.path("aggregate_type")));
        JsonNode payload = outbox.path("payload");

        ObjectNode object = objectMapper.createObjectNode();
        // 1) copy PSP keys through unchanged, except defensively-stripped card subtrees.
        if (payload.isObject()) {
            payload.fields().forEachRemaining(e -> {
                if (!FORBIDDEN_OBJECT_KEYS.contains(e.getKey().toLowerCase(java.util.Locale.ROOT))) {
                    object.set(e.getKey(), e.getValue());
                }
            });
        }

        // 2) overlay the stable discriminator.
        object.put("object", isRefund ? "refund" : "payment");

        String id;
        if (isRefund) {
            id = firstNonBlank(text(payload.path("refund_id")), text(outbox.path("aggregate_id")));
        } else {
            id = firstNonBlank(text(payload.path("payment_id")), text(outbox.path("aggregate_id")));
        }
        // 'id' is placed (or overwritten) deterministically; payment_id is kept for refunds (it passed
        // through in step 1 and is not the chosen 'id').
        object.put("id", id != null ? id : "");
        return object;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String s = node.asText();
        return s.isEmpty() ? null : s;
    }
}
