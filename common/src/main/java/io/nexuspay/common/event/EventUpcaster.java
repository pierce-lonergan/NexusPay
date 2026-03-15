package io.nexuspay.common.event;

/**
 * Transforms an event payload from one version to the next.
 *
 * <p>Upcasters enable backward-compatible event schema evolution. When a consumer
 * reads an event at version N, the upcaster chain applies transformations
 * N → N+1 → ... → current version, so consumers always work with the latest schema.</p>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * public class PaymentCapturedV1ToV2Upcaster implements EventUpcaster {
 *     public String eventType()  { return "PaymentCaptured"; }
 *     public int fromVersion()   { return 1; }
 *     public int toVersion()     { return 2; }
 *     public String upcast(String payload) {
 *         // Add "currency" field with default "USD"
 *         ObjectNode node = (ObjectNode) objectMapper.readTree(payload);
 *         if (!node.has("currency")) node.put("currency", "USD");
 *         return objectMapper.writeValueAsString(node);
 *     }
 * }
 * }</pre>
 *
 * @since 0.2.0 (Sprint 2.2)
 */
public interface EventUpcaster {

    /**
     * The event type this upcaster applies to (e.g., "PaymentCaptured").
     */
    String eventType();

    /**
     * The source version this upcaster reads.
     */
    int fromVersion();

    /**
     * The target version this upcaster produces.
     */
    int toVersion();

    /**
     * Transforms the JSON payload from {@link #fromVersion()} to {@link #toVersion()}.
     *
     * @param payload the event payload in JSON format
     * @return the transformed payload
     */
    String upcast(String payload);
}
