package io.nexuspay.ledger.adapter.in.event;

import io.nexuspay.common.event.EventTypes;
import io.nexuspay.common.rls.TenantWorkRunner;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase.CreateJournalEntryCommand;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase.CreateJournalEntryCommand.PostingLine;
import io.nexuspay.ledger.application.EnsureAccountsExistUseCase;
import io.nexuspay.ledger.application.port.JournalEntryRepository;
import io.nexuspay.ledger.domain.JournalEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Kafka entry point that books captures and refunds.
 * Pins the debit/credit directions, currency uppercasing, idempotency guard,
 * tenant resolution (RLS scoping) and error propagation for redelivery/DLT.
 */
class PaymentEventConsumerTest {

    private CreateJournalEntryUseCase createJournalEntry;
    private EnsureAccountsExistUseCase ensureAccounts;
    private JournalEntryRepository journalEntryRepository;
    private TenantWorkRunner tenantWork;
    private PaymentEventConsumer consumer;

    @BeforeEach
    void setUp() {
        createJournalEntry = mock(CreateJournalEntryUseCase.class);
        ensureAccounts = mock(EnsureAccountsExistUseCase.class);
        journalEntryRepository = mock(JournalEntryRepository.class);
        tenantWork = mock(TenantWorkRunner.class);
        consumer = new PaymentEventConsumer(createJournalEntry, ensureAccounts, journalEntryRepository, tenantWork);

        // bindTenant just runs the Runnable synchronously (dormant impl semantics).
        doAnswer(inv -> {
            Runnable work = inv.getArgument(1);
            work.run();
            return null;
        }).when(tenantWork).bindTenant(anyString(), any(Runnable.class));

        when(createJournalEntry.execute(any(CreateJournalEntryCommand.class)))
                .thenReturn(mock(JournalEntry.class));
    }

    private Map<String, Object> captureEvent(String tenantId, long amount, String currency) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", amount);
        payload.put("currency", currency);
        Map<String, Object> event = new HashMap<>();
        event.put("event_type", EventTypes.PAYMENT_CAPTURED);
        event.put("aggregate_id", "pi_123");
        event.put("event_id", "evt_abc");
        if (tenantId != null) {
            event.put("tenant_id", tenantId);
        }
        event.put("payload", payload);
        return event;
    }

    private Map<String, Object> refundEvent(String paymentId, long amount, String currency) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", amount);
        payload.put("currency", currency);
        payload.put("payment_id", paymentId);
        Map<String, Object> event = new HashMap<>();
        event.put("event_type", EventTypes.REFUND_COMPLETED);
        event.put("aggregate_id", "ref_999");
        event.put("event_id", "evt_ref");
        event.put("tenant_id", "tenant-r");
        event.put("payload", payload);
        return event;
    }

    private CreateJournalEntryCommand capturedCommand() {
        ArgumentCaptor<CreateJournalEntryCommand> captor =
                ArgumentCaptor.forClass(CreateJournalEntryCommand.class);
        verify(createJournalEntry).execute(captor.capture());
        return captor.getValue();
    }

    @Test
    void paymentCaptured_booksDrMerchantRecvCrCustomerLiab_currencyUppercased() {
        consumer.onPaymentEvent(captureEvent("tenant-1", 10000L, "usd"));

        CreateJournalEntryCommand cmd = capturedCommand();
        assertThat(cmd.description()).isEqualTo("Payment captured");
        assertThat(cmd.paymentReference()).isEqualTo("pi_123");
        assertThat(cmd.tenantId()).isEqualTo("tenant-1");

        List<PostingLine> postings = cmd.postings();
        assertThat(postings).hasSize(2);
        PostingLine merchant = postings.stream()
                .filter(p -> p.ledgerAccountId().equals("la_merchant_recv_usd")).findFirst().orElseThrow();
        PostingLine customer = postings.stream()
                .filter(p -> p.ledgerAccountId().equals("la_customer_liab_usd")).findFirst().orElseThrow();
        assertThat(merchant.amount()).isEqualTo(10000L);   // DR merchant_recv (+)
        assertThat(customer.amount()).isEqualTo(-10000L);  // CR customer_liab (-)
        assertThat(merchant.currency()).isEqualTo("USD");
        assertThat(customer.currency()).isEqualTo("USD");

        verify(ensureAccounts).ensureAccountsForCurrency("USD", "tenant-1");
        assertThat(cmd.metadata()).containsEntry("event_id", "evt_abc");
    }

    @Test
    void refundCompleted_booksDrRefundsCrMerchantRecv_metadataCarriesPaymentId() {
        consumer.onPaymentEvent(refundEvent("pi_orig", 4000L, "eur"));

        CreateJournalEntryCommand cmd = capturedCommand();
        assertThat(cmd.description()).isEqualTo("Refund completed");
        assertThat(cmd.paymentReference()).isEqualTo("ref_999"); // refundId from aggregate_id

        List<PostingLine> postings = cmd.postings();
        PostingLine refunds = postings.stream()
                .filter(p -> p.ledgerAccountId().equals("la_refunds_eur")).findFirst().orElseThrow();
        PostingLine merchant = postings.stream()
                .filter(p -> p.ledgerAccountId().equals("la_merchant_recv_eur")).findFirst().orElseThrow();
        assertThat(refunds.amount()).isEqualTo(4000L);    // DR refunds (+)
        assertThat(merchant.amount()).isEqualTo(-4000L);  // CR merchant_recv (-)

        assertThat(cmd.metadata())
                .containsEntry("event_id", "evt_ref")
                .containsEntry("payment_id", "pi_orig"); // from payload.payment_id
    }

    @Test
    void capture_idempotency_doesNotCreateEntryWhenAlreadyBooked() {
        when(journalEntryRepository.existsByPaymentReferenceAndDescription("pi_123", "Payment captured"))
                .thenReturn(true);

        consumer.onPaymentEvent(captureEvent("tenant-1", 10000L, "USD"));

        verify(createJournalEntry, never()).execute(any(CreateJournalEntryCommand.class));
    }

    @Test
    void refund_idempotency_doesNotCreateEntryWhenAlreadyBooked() {
        when(journalEntryRepository.existsByPaymentReferenceAndDescription("ref_999", "Refund completed"))
                .thenReturn(true);

        consumer.onPaymentEvent(refundEvent("pi_orig", 4000L, "USD"));

        verify(createJournalEntry, never()).execute(any(CreateJournalEntryCommand.class));
    }

    @Test
    void unknownEventType_createsNoJournalEntry() {
        Map<String, Object> event = new HashMap<>();
        event.put("event_type", "SomethingElse");
        event.put("aggregate_id", "x");
        event.put("tenant_id", "tenant-1");
        event.put("payload", new HashMap<String, Object>());

        consumer.onPaymentEvent(event);

        verify(createJournalEntry, never()).execute(any(CreateJournalEntryCommand.class));
    }

    @Test
    void missingEventType_createsNoJournalEntry() {
        Map<String, Object> event = new HashMap<>();
        event.put("aggregate_id", "x");
        event.put("tenant_id", "tenant-1");
        event.put("payload", new HashMap<String, Object>());

        consumer.onPaymentEvent(event);

        verify(createJournalEntry, never()).execute(any(CreateJournalEntryCommand.class));
    }

    @Test
    void tenantResolution_topLevelTenantWins() {
        consumer.onPaymentEvent(captureEvent("tenant-top", 1000L, "USD"));

        verify(tenantWork).bindTenant(eq("tenant-top"), any(Runnable.class));
        assertThat(capturedCommand().tenantId()).isEqualTo("tenant-top");
    }

    @Test
    void tenantResolution_fallsBackToPayloadTenantWhenTopLevelAbsent() {
        Map<String, Object> event = captureEvent(null, 1000L, "USD");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        payload.put("tenant_id", "tenant-payload");

        consumer.onPaymentEvent(event);

        verify(tenantWork).bindTenant(eq("tenant-payload"), any(Runnable.class));
        assertThat(capturedCommand().tenantId()).isEqualTo("tenant-payload");
    }

    @Test
    void tenantResolution_blankTenantFallsBackToDefault() {
        Map<String, Object> event = captureEvent("   ", 1000L, "USD");

        consumer.onPaymentEvent(event);

        verify(tenantWork).bindTenant(eq(EnsureAccountsExistUseCase.DEFAULT_TENANT), any(Runnable.class));
        assertThat(capturedCommand().tenantId()).isEqualTo(EnsureAccountsExistUseCase.DEFAULT_TENANT);
    }

    @Test
    void tenantResolution_noTenantAnywhereFallsBackToDefault() {
        Map<String, Object> event = captureEvent(null, 1000L, "USD");

        consumer.onPaymentEvent(event);

        verify(tenantWork).bindTenant(eq(EnsureAccountsExistUseCase.DEFAULT_TENANT), any(Runnable.class));
    }

    @Test
    void errorPath_runtimeExceptionPropagatesOutOfOnPaymentEvent() {
        when(createJournalEntry.execute(any(CreateJournalEntryCommand.class)))
                .thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> consumer.onPaymentEvent(captureEvent("tenant-1", 1000L, "USD")))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");
    }

    @Test
    void amountReadAsIntegerFromMap_isHandledViaNumberCast() {
        // Kafka/JSON deserialization frequently yields Integer for small amounts.
        Map<String, Object> event = captureEvent("tenant-1", 0L, "USD");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        payload.put("amount", Integer.valueOf(750)); // Integer, not Long

        consumer.onPaymentEvent(event);

        CreateJournalEntryCommand cmd = capturedCommand();
        PostingLine merchant = cmd.postings().stream()
                .filter(p -> p.ledgerAccountId().equals("la_merchant_recv_usd")).findFirst().orElseThrow();
        assertThat(merchant.amount()).isEqualTo(750L);
    }
}
