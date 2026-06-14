package io.nexuspay.billing.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * State-machine + money-total tests for {@link Invoice}. The lifecycle
 * (DRAFT -> OPEN -> PAID / VOID / UNCOLLECTIBLE) guards each transition with an
 * {@link IllegalStateException}; a broken guard could pay a draft or void a paid
 * invoice and corrupt accounts-receivable. Totals are recomputed from line items,
 * so the arithmetic (including credit/negative line items) is asserted exactly.
 */
class InvoiceTest {

    private static final Instant PERIOD_START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant PERIOD_END = Instant.parse("2026-02-01T00:00:00Z");

    private static Invoice draft() {
        return Invoice.createForSubscription("t1", "sub_1", "cust_1", "USD", PERIOD_START, PERIOD_END);
    }

    private static InvoiceLineItem item(long amount) {
        return new InvoiceLineItem("ili_x", "inv_x", "t1", "charge", amount, "USD",
                1, false, PERIOD_START, PERIOD_END);
    }

    // ---- creation ----

    @Test
    void createForSubscriptionStartsDraftWithDueDateAtPeriodEnd() {
        Invoice inv = draft();
        assertThat(inv.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(inv.getDueDate()).isEqualTo(PERIOD_END);
        assertThat(inv.getId()).startsWith("inv_");
        assertThat(inv.getPeriodStart()).isEqualTo(PERIOD_START);
        assertThat(inv.getPeriodEnd()).isEqualTo(PERIOD_END);
    }

    // ---- totals ----

    @Test
    void addLineItemRecalculatesSubtotalAndTotal() {
        Invoice inv = draft();
        inv.addLineItem(item(1500));
        assertThat(inv.getSubtotal()).isEqualTo(1500);
        assertThat(inv.getTax()).isEqualTo(0);
        assertThat(inv.getTotal()).isEqualTo(1500);
    }

    @Test
    void multipleLineItemsSum() {
        Invoice inv = draft();
        inv.addLineItem(item(1500));
        inv.addLineItem(item(500));
        assertThat(inv.getSubtotal()).isEqualTo(2000);
        assertThat(inv.getTotal()).isEqualTo(2000);
    }

    @Test
    void creditLineItemLowersTotal() {
        Invoice inv = draft();
        inv.addLineItem(item(2000));
        inv.addLineItem(item(-500)); // proration credit
        assertThat(inv.getTotal()).isEqualTo(1500);
    }

    // ---- finalise ----

    @Test
    void finaliseFromDraftOpensAndSetsAmountDue() {
        Invoice inv = draft();
        inv.addLineItem(item(1500));
        inv.finalise();
        assertThat(inv.getStatus()).isEqualTo(InvoiceStatus.OPEN);
        assertThat(inv.getAmountDue()).isEqualTo(1500);
    }

    @Test
    void finaliseFromOpenThrows() {
        Invoice inv = draft();
        inv.addLineItem(item(1500));
        inv.finalise();
        assertThatThrownBy(inv::finalise).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void finaliseFromPaidThrows() {
        Invoice inv = draft();
        inv.addLineItem(item(1500));
        inv.finalise();
        inv.markPaid("pay_1");
        assertThatThrownBy(inv::finalise).isInstanceOf(IllegalStateException.class);
    }

    // ---- markPaid ----

    @Test
    void markPaidFromOpenSettlesInvoice() {
        Invoice inv = draft();
        inv.addLineItem(item(1500));
        inv.finalise();
        inv.markPaid("pay_42");
        assertThat(inv.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(inv.getAmountPaid()).isEqualTo(1500);
        assertThat(inv.getAmountDue()).isEqualTo(0);
        assertThat(inv.getPaymentId()).isEqualTo("pay_42");
        assertThat(inv.getPaidAt()).isNotNull();
    }

    @Test
    void markPaidFromDraftThrows() {
        Invoice inv = draft();
        inv.addLineItem(item(1500));
        assertThatThrownBy(() -> inv.markPaid("pay_1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void markPaidTwiceThrows() {
        Invoice inv = draft();
        inv.addLineItem(item(1500));
        inv.finalise();
        inv.markPaid("pay_1");
        assertThatThrownBy(() -> inv.markPaid("pay_2")).isInstanceOf(IllegalStateException.class);
    }

    // ---- void ----

    @Test
    void voidFromDraftSetsVoidAndZeroesAmountDue() {
        Invoice inv = draft();
        inv.addLineItem(item(1500));
        inv.voidInvoice();
        assertThat(inv.getStatus()).isEqualTo(InvoiceStatus.VOID);
        assertThat(inv.getAmountDue()).isEqualTo(0);
    }

    @Test
    void voidFromOpenSetsVoid() {
        Invoice inv = draft();
        inv.addLineItem(item(1500));
        inv.finalise();
        inv.voidInvoice();
        assertThat(inv.getStatus()).isEqualTo(InvoiceStatus.VOID);
        assertThat(inv.getAmountDue()).isEqualTo(0);
    }

    @Test
    void voidPaidInvoiceThrows() {
        Invoice inv = draft();
        inv.addLineItem(item(1500));
        inv.finalise();
        inv.markPaid("pay_1");
        assertThatThrownBy(inv::voidInvoice).isInstanceOf(IllegalStateException.class);
    }

    // ---- uncollectible ----

    @Test
    void markUncollectibleSetsStatus() {
        Invoice inv = draft();
        inv.addLineItem(item(1500));
        inv.finalise();
        inv.markUncollectible();
        assertThat(inv.getStatus()).isEqualTo(InvoiceStatus.UNCOLLECTIBLE);
    }

    // ---- defensive collection ----

    @Test
    void getLineItemsIsUnmodifiable() {
        Invoice inv = draft();
        inv.addLineItem(item(1500));
        assertThat(inv.getLineItems()).hasSize(1);
        assertThatThrownBy(() -> inv.getLineItems().add(item(1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
