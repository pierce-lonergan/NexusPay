package io.nexuspay.billing.application.service;

import io.nexuspay.billing.application.port.out.BillingOutboxPort;
import io.nexuspay.billing.application.port.out.InvoiceRepository;
import io.nexuspay.billing.application.port.out.PaymentPort;
import io.nexuspay.billing.domain.Invoice;
import io.nexuspay.billing.domain.InvoiceLineItem;
import io.nexuspay.billing.domain.InvoiceStatus;
import io.nexuspay.billing.domain.Price;
import io.nexuspay.billing.domain.PricingModel;
import io.nexuspay.billing.domain.Subscription;
import io.nexuspay.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Orchestration tests for {@link InvoiceGenerationService}: invoice creation +
 * outbox publish, and the status guard on {@code collectPayment} that refuses to
 * charge a non-OPEN invoice (double-charge / idempotency protection). Verifies the
 * charge is keyed on the invoice's amountDue + id so a network retry dedupes.
 */
class InvoiceGenerationServiceTest {

    private InvoiceRepository invoiceRepository;
    private PaymentPort paymentPort;
    private BillingOutboxPort outboxPort;
    private InvoiceGenerationService service;

    @BeforeEach
    void setUp() {
        invoiceRepository = mock(InvoiceRepository.class);
        paymentPort = mock(PaymentPort.class);
        outboxPort = mock(BillingOutboxPort.class);
        service = new InvoiceGenerationService(invoiceRepository, paymentPort, outboxPort);
        // save() returns the same invoice it was handed (the service reassigns the result).
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceRepository.saveLineItem(any(InvoiceLineItem.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private static Price flat(long unitAmount, String currency) {
        Price p = new Price();
        p.setPricingModel(PricingModel.FLAT);
        p.setUnitAmount(unitAmount);
        p.setCurrency(currency);
        return p;
    }

    private static Subscription subscription(int quantity) {
        Subscription s = new Subscription();
        s.setId("sub_1");
        s.setTenantId("t1");
        s.setCustomerId("cust_1");
        s.setQuantity(quantity);
        s.setCurrentPeriodStart(Instant.parse("2026-01-01T00:00:00Z"));
        s.setCurrentPeriodEnd(Instant.parse("2026-02-01T00:00:00Z"));
        return s;
    }

    private static Invoice openInvoice(long total) {
        Invoice inv = Invoice.createForSubscription("t1", "sub_1", "cust_1", "USD",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"));
        inv.addLineItem(new InvoiceLineItem("ili_1", inv.getId(), "t1", "charge", total, "USD",
                1, false, inv.getPeriodStart(), inv.getPeriodEnd()));
        inv.finalise();
        return inv;
    }

    // ---- generateInvoice ----

    @Test
    void generateInvoiceFinalisesWithPriceAmountAndPublishesEvent() {
        Subscription s = subscription(3);
        Price price = flat(2500, "USD"); // FLAT ignores quantity -> total 2500

        Invoice result = service.generateInvoice(s, price);

        assertThat(result.getStatus()).isEqualTo(InvoiceStatus.OPEN);
        assertThat(result.getTotal()).isEqualTo(2500);
        assertThat(result.getCurrency()).isEqualTo("USD");
        assertThat(result.getTenantId()).isEqualTo("t1");
        assertThat(result.getCustomerId()).isEqualTo("cust_1");
        assertThat(result.getLineItems()).hasSize(1);

        verify(invoiceRepository).save(any(Invoice.class));
        verify(invoiceRepository).saveLineItem(any(InvoiceLineItem.class));
        verify(outboxPort).publishEvent(eq("Invoice"), eq(result.getId()), eq("InvoiceCreated"),
                any(Map.class), eq("t1"));
    }

    @Test
    void generateInvoicePublishesTotalAndOpenStatusInPayload() {
        Subscription s = subscription(1);
        Price price = flat(1234, "EUR");

        Invoice result = service.generateInvoice(s, price);

        ArgumentCaptor<Map> payload = ArgumentCaptor.forClass(Map.class);
        verify(outboxPort).publishEvent(eq("Invoice"), anyString(), eq("InvoiceCreated"),
                payload.capture(), eq("t1"));
        assertThat(payload.getValue())
                .containsEntry("total", 1234L)
                .containsEntry("currency", "EUR")
                .containsEntry("status", "OPEN")
                .containsEntry("subscriptionId", "sub_1");
    }

    @Test
    void generateInvoiceUsesPerUnitQuantityMath() {
        Subscription s = subscription(4);
        Price price = new Price();
        price.setPricingModel(PricingModel.PER_UNIT);
        price.setUnitAmount(1000L);
        price.setCurrency("USD");

        Invoice result = service.generateInvoice(s, price);

        assertThat(result.getTotal()).isEqualTo(4000); // 1000 * 4
    }

    // ---- collectPayment guard ----

    @Test
    void collectPaymentRefusesNonOpenDraftInvoiceAndNeverCharges() {
        Invoice draft = Invoice.createForSubscription("t1", "sub_1", "cust_1", "USD",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"));

        boolean charged = service.collectPayment(draft, "pm_1");

        assertThat(charged).isFalse();
        verify(paymentPort, never()).collectPayment(anyString(), anyString(), anyString(),
                anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void collectPaymentRefusesPaidInvoiceAndNeverCharges() {
        Invoice paid = openInvoice(5000);
        paid.markPaid("pay_prev");

        boolean charged = service.collectPayment(paid, "pm_1");

        assertThat(charged).isFalse();
        verify(paymentPort, never()).collectPayment(anyString(), anyString(), anyString(),
                anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void collectPaymentRefusesVoidInvoiceAndNeverCharges() {
        Invoice voided = openInvoice(5000);
        voided.voidInvoice();

        boolean charged = service.collectPayment(voided, "pm_1");

        assertThat(charged).isFalse();
        verify(paymentPort, never()).collectPayment(anyString(), anyString(), anyString(),
                anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void collectPaymentOnSuccessMarksPaidAndPublishes() {
        Invoice open = openInvoice(5000);
        when(paymentPort.collectPayment(eq("t1"), eq("cust_1"), eq("pm_1"), eq(5000L),
                eq("USD"), anyString(), eq(open.getId())))
                .thenReturn(PaymentPort.PaymentResult.success("pay_ok"));

        boolean charged = service.collectPayment(open, "pm_1");

        assertThat(charged).isTrue();
        assertThat(open.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(open.getPaymentId()).isEqualTo("pay_ok");
        verify(invoiceRepository).save(open);
        verify(outboxPort).publishEvent(eq("Invoice"), eq(open.getId()), eq("InvoicePaid"),
                any(Map.class), eq("t1"));
    }

    @Test
    void collectPaymentChargesAmountDueAndUsesInvoiceIdAsReference() {
        Invoice open = openInvoice(7777);
        when(paymentPort.collectPayment(anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString()))
                .thenReturn(PaymentPort.PaymentResult.success("pay_ok"));

        service.collectPayment(open, "pm_1");

        // amount == amountDue (7777) and the idempotency reference == invoice id.
        verify(paymentPort).collectPayment(eq("t1"), eq("cust_1"), eq("pm_1"), eq(7777L),
                eq("USD"), anyString(), eq(open.getId()));
    }

    @Test
    void collectPaymentOnFailureReturnsFalseLeavesInvoiceOpenAndPublishesNothing() {
        Invoice open = openInvoice(5000);
        when(paymentPort.collectPayment(anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString()))
                .thenReturn(PaymentPort.PaymentResult.failure("card_declined"));

        boolean charged = service.collectPayment(open, "pm_1");

        assertThat(charged).isFalse();
        assertThat(open.getStatus()).isEqualTo(InvoiceStatus.OPEN);
        verify(outboxPort, never()).publishEvent(anyString(), anyString(), eq("InvoicePaid"),
                any(Map.class), anyString());
    }

    // ---- SEC-26: tenant-scoped by-id queries (cross-tenant IDOR teeth) ----

    @Test
    void findByIdScopesToTenant_andDoesNotFallBackToUnscopedLookup() {
        Invoice owned = openInvoice(5000);
        when(invoiceRepository.findByIdAndTenantId(owned.getId(), "t1")).thenReturn(Optional.of(owned));

        assertThat(service.findById(owned.getId(), "t1")).contains(owned);

        // The unscoped finder must NEVER be used by the controller-facing read.
        verify(invoiceRepository).findByIdAndTenantId(owned.getId(), "t1");
        verify(invoiceRepository, never()).findById(anyString());
    }

    @Test
    void findByIdForeignTenantReturnsEmpty() {
        // Repo finder returns empty for a foreign tenant (absent OR not owned).
        when(invoiceRepository.findByIdAndTenantId("inv_victim", "attacker"))
                .thenReturn(Optional.empty());

        assertThat(service.findById("inv_victim", "attacker")).isEmpty();
    }

    @Test
    void getLineItemsAssertsInvoiceOwnershipFirst_andThrows404ForForeignTenant() {
        when(invoiceRepository.findByIdAndTenantId("inv_victim", "attacker"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getLineItems("inv_victim", "attacker"))
                .isInstanceOf(ResourceNotFoundException.class);

        // No line items are read for a non-owned invoice — no cross-tenant line-item leak.
        verify(invoiceRepository, never()).findLineItemsByInvoice(anyString());
    }

    @Test
    void getLineItemsReturnsItemsWhenInvoiceOwnedByTenant() {
        Invoice owned = openInvoice(5000);
        when(invoiceRepository.findByIdAndTenantId(owned.getId(), "t1")).thenReturn(Optional.of(owned));
        InvoiceLineItem li = new InvoiceLineItem("ili_1", owned.getId(), "t1", "charge", 5000, "USD",
                1, false, owned.getPeriodStart(), owned.getPeriodEnd());
        when(invoiceRepository.findLineItemsByInvoice(owned.getId())).thenReturn(List.of(li));

        assertThat(service.getLineItems(owned.getId(), "t1")).containsExactly(li);
    }
}
