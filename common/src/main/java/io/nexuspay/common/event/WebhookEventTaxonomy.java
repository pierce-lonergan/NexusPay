package io.nexuspay.common.event;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * INT-1: the single source of truth for the canonical, merchant-facing OUTBOUND webhook event
 * taxonomy and the internal→dotted mapping.
 *
 * <p>Internal events are emitted in PascalCase ({@link EventTypes}) and travel byte-unchanged on the
 * internal Kafka/outbox payload (ledger/analytics consumers depend on those exact bytes). At webhook
 * SEND time only, the delivery serializer translates the internal type to its dotted, lowercase
 * canonical name (Stripe-shaped: {@code payment.succeeded}, {@code payment.refunded}, …) which is what
 * a merchant subscribes to and sees on the wire. The registration validator accepts ONLY these dotted
 * names (plus {@code "*"} wildcard), so a merchant can never register a name the serializer would not
 * emit.</p>
 *
 * <p>This class is consumed by BOTH:
 * <ul>
 *   <li>{@code WebhookDeliveryService} / {@code WebhookEnvelopeSerializer} (gateway-api) — internal→dotted
 *       at send time, and matching the dotted subscription stored on the endpoint;</li>
 *   <li>{@code CanonicalWebhookEventsValidator} (gateway-api) — is-this-a-valid-dotted-name at
 *       registration.</li>
 * </ul>
 * It lives in {@code common} so both module uses share one definition (no drift between what is
 * deliverable and what is registerable).</p>
 *
 * <p>The map covers EXACTLY the 6 payment/refund internal types that {@code mapHyperSwitchEventType}
 * can emit plus the two domain-emitted {@code PaymentCreated}/{@code RefundCreated} types — the full
 * set of internal types that can reach a webhook. Any other internal type has no dotted mapping and is
 * NOT deliverable on the canonical contract ({@link #toDotted(String)} returns {@code null}).</p>
 */
public final class WebhookEventTaxonomy {

    private WebhookEventTaxonomy() {
    }

    /**
     * Internal (PascalCase {@link EventTypes}) → dotted canonical name. Insertion order is preserved so
     * {@link #CANONICAL} and the validator message are deterministic.
     */
    private static final Map<String, String> INTERNAL_TO_DOTTED = Map.ofEntries(
            Map.entry(EventTypes.PAYMENT_CREATED, "payment.created"),
            Map.entry(EventTypes.PAYMENT_AUTHORIZED, "payment.authorized"),
            Map.entry(EventTypes.PAYMENT_CAPTURED, "payment.succeeded"),
            Map.entry(EventTypes.PAYMENT_FAILED, "payment.failed"),
            Map.entry(EventTypes.PAYMENT_VOIDED, "payment.canceled"),
            Map.entry(EventTypes.REFUND_CREATED, "payment.refund.created"),
            Map.entry(EventTypes.REFUND_COMPLETED, "payment.refunded"),
            Map.entry(EventTypes.REFUND_FAILED, "payment.refund.failed"));

    /**
     * The canonical dotted names a merchant may subscribe to (the map's values). {@code "*"} is also
     * accepted by {@link #isValid(String)} but is NOT a member of this set (it is a wildcard, not a
     * concrete event name).
     */
    public static final Set<String> CANONICAL =
            Set.copyOf(new LinkedHashSet<>(INTERNAL_TO_DOTTED.values()));

    /**
     * Translates an internal PascalCase event type to its dotted canonical name.
     *
     * @return the dotted name, or {@code null} when the internal type has no canonical mapping
     *         (such an event is NOT deliverable on the canonical outbound contract).
     */
    public static String toDotted(String internal) {
        return internal == null ? null : INTERNAL_TO_DOTTED.get(internal);
    }

    /**
     * @return {@code true} if {@code dotted} is the wildcard {@code "*"} or one of the canonical dotted
     *         event names — the set of values a merchant may register a subscription for.
     */
    public static boolean isValid(String dotted) {
        return "*".equals(dotted) || CANONICAL.contains(dotted);
    }
}
