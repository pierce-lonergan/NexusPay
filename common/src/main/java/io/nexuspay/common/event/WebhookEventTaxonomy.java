package io.nexuspay.common.event;

import java.util.LinkedHashMap;
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
 * <p>The map covers the payment/refund internal types that {@code mapHyperSwitchEventType} can emit
 * plus the two domain-emitted {@code PaymentCreated}/{@code RefundCreated} types, AND (TEST-2) the seven
 * dispute/chargeback internal types the dispute domain emits through its transactional outbox
 * ({@code dispute.created}, {@code dispute.funds_withdrawn}, {@code dispute.evidence_needed},
 * {@code dispute.evidence_submitted}, {@code dispute.won}, {@code dispute.lost}, {@code dispute.closed})
 * — the full set of internal types
 * that can reach a webhook. Any other internal type has no dotted mapping and is NOT deliverable on the
 * canonical contract ({@link #toDotted(String)} returns {@code null}).</p>
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
            Map.entry(EventTypes.REFUND_FAILED, "payment.refund.failed"),
            // TEST-2: dispute / chargeback lifecycle. The dispute domain emits these internal types
            // through its transactional outbox; the serializer translates them to the dotted canonical
            // names below at send time. The transition→event mapping (documented on
            // DisputeLifecycleService) is:
            //   openDispute      -> DisputeCreated           -> dispute.created
            //   openDispute      -> DisputeFundsWithdrawn    -> dispute.funds_withdrawn (at the chargeback-reserve point)
            //   requestEvidence  -> DisputeEvidenceNeeded    -> dispute.evidence_needed
            //   submitEvidence   -> DisputeEvidenceSubmitted -> dispute.evidence_submitted
            //   win              -> DisputeWon               -> dispute.won
            //   lose             -> DisputeLost              -> dispute.lost
            //   expire           -> DisputeClosed            -> dispute.closed (terminal close)
            Map.entry(EventTypes.DISPUTE_CREATED, "dispute.created"),
            Map.entry(EventTypes.DISPUTE_FUNDS_WITHDRAWN, "dispute.funds_withdrawn"),
            Map.entry(EventTypes.DISPUTE_EVIDENCE_NEEDED, "dispute.evidence_needed"),
            Map.entry(EventTypes.DISPUTE_EVIDENCE_SUBMITTED, "dispute.evidence_submitted"),
            Map.entry(EventTypes.DISPUTE_WON, "dispute.won"),
            Map.entry(EventTypes.DISPUTE_LOST, "dispute.lost"),
            Map.entry(EventTypes.DISPUTE_CLOSED, "dispute.closed"));

    /**
     * The canonical dotted names a merchant may subscribe to (the map's values). {@code "*"} is also
     * accepted by {@link #isValid(String)} but is NOT a member of this set (it is a wildcard, not a
     * concrete event name).
     */
    public static final Set<String> CANONICAL =
            Set.copyOf(new LinkedHashSet<>(INTERNAL_TO_DOTTED.values()));

    /**
     * TEST-4a: the INVERSE of {@link #INTERNAL_TO_DOTTED}, built once from the SAME single map so the
     * reverse direction can never drift from the forward one. dotted → internal PascalCase. The mapping is
     * a bijection (each dotted name has exactly one internal source), so inverting is unambiguous.
     */
    private static final Map<String, String> DOTTED_TO_INTERNAL;

    static {
        Map<String, String> inverse = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : INTERNAL_TO_DOTTED.entrySet()) {
            inverse.put(e.getValue(), e.getKey());
        }
        DOTTED_TO_INTERNAL = Map.copyOf(inverse);
    }

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

    /**
     * TEST-4a: the EXACT inverse of {@link #toDotted(String)} — translates a dotted canonical name back to
     * its internal PascalCase {@link EventTypes} constant. Single-sourced off {@link #INTERNAL_TO_DOTTED}
     * (built into {@link #DOTTED_TO_INTERNAL}) so it can never disagree with the forward direction.
     *
     * <p>Used by the test-event trigger (synthesizing an {@code event_outbox} row from a caller-supplied
     * dotted type). Returns {@code null} when {@code dotted} is not a concrete canonical name (including the
     * {@code "*"} wildcard, which is NOT a single event), so the caller fails closed with a 400.</p>
     *
     * @return the internal PascalCase type, or {@code null} when {@code dotted} is not canonical.
     */
    public static String fromDotted(String dotted) {
        return dotted == null ? null : DOTTED_TO_INTERNAL.get(dotted);
    }

    /**
     * TEST-4a: the aggregate type ({@link EventTypes#AGGREGATE_PAYMENT} / {@link EventTypes#AGGREGATE_REFUND}
     * / {@link EventTypes#AGGREGATE_DISPUTE}) for an internal PascalCase event type, keyed off the internal
     * prefix — NOT the dotted prefix.
     *
     * <p>This distinction matters: the {@code payment.*} dotted family maps to BOTH Payment-prefixed AND
     * Refund-prefixed internal types ({@code payment.refunded → RefundCompleted}), and
     * {@code WebhookEnvelopeSerializer.normalizeObject} discriminates the {@code data.object} on the
     * AGGREGATE type. Keying off the dotted prefix would normalize a refund test event as a payment object.
     * {@code Refund*} → Refund, {@code Dispute*} → Dispute, everything else → Payment.</p>
     *
     * @param internal the internal PascalCase event type (e.g. from {@link #fromDotted(String)})
     * @return the matching aggregate type constant; {@code null} when {@code internal} is null.
     */
    public static String aggregateTypeFor(String internal) {
        if (internal == null) {
            return null;
        }
        if (internal.startsWith("Refund")) {
            return EventTypes.AGGREGATE_REFUND;
        }
        if (internal.startsWith("Dispute")) {
            return EventTypes.AGGREGATE_DISPUTE;
        }
        return EventTypes.AGGREGATE_PAYMENT;
    }
}
