package io.nexuspay.common.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEST-4a: pins the reverse taxonomy added for the test-event trigger. {@code fromDotted} must be the EXACT
 * inverse of {@code toDotted} for every canonical name (round-trip), and {@code aggregateTypeFor} must key
 * off the INTERNAL prefix (so {@code payment.refunded → RefundCompleted → Refund}, not Payment).
 */
class WebhookEventTaxonomyReverseTest {

    @Test
    void fromDotted_isExactInverseOfToDotted_forEveryCanonical() {
        for (String dotted : WebhookEventTaxonomy.CANONICAL) {
            String internal = WebhookEventTaxonomy.fromDotted(dotted);
            assertThat(internal)
                    .as("fromDotted(%s) must be non-null for a canonical name", dotted)
                    .isNotNull();
            // round-trip: internal -> dotted gets us back to the original dotted name.
            assertThat(WebhookEventTaxonomy.toDotted(internal))
                    .as("round-trip toDotted(fromDotted(%s))", dotted)
                    .isEqualTo(dotted);
        }
    }

    @Test
    void fromDotted_isNull_forWildcardAndUnknownAndNull() {
        assertThat(WebhookEventTaxonomy.fromDotted("*")).isNull();
        assertThat(WebhookEventTaxonomy.fromDotted("not.a.real.type")).isNull();
        assertThat(WebhookEventTaxonomy.fromDotted(null)).isNull();
    }

    @Test
    void aggregateTypeFor_keysOffInternalPrefix() {
        // payment.succeeded -> PaymentCaptured -> Payment
        assertThat(WebhookEventTaxonomy.aggregateTypeFor(
                WebhookEventTaxonomy.fromDotted("payment.succeeded")))
                .isEqualTo(EventTypes.AGGREGATE_PAYMENT);
        // payment.refunded -> RefundCompleted -> Refund (NOT Payment, despite the dotted "payment." prefix)
        assertThat(WebhookEventTaxonomy.aggregateTypeFor(
                WebhookEventTaxonomy.fromDotted("payment.refunded")))
                .isEqualTo(EventTypes.AGGREGATE_REFUND);
        // dispute.created -> DisputeCreated -> Dispute
        assertThat(WebhookEventTaxonomy.aggregateTypeFor(
                WebhookEventTaxonomy.fromDotted("dispute.created")))
                .isEqualTo(EventTypes.AGGREGATE_DISPUTE);
    }

    @Test
    void aggregateTypeFor_null_returnsNull() {
        assertThat(WebhookEventTaxonomy.aggregateTypeFor(null)).isNull();
    }
}
