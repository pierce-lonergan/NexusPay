package io.nexuspay.billing.adapter.out.payment;

import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.application.screening.CallContext;
import io.nexuspay.payment.application.screening.ScreeningMode;
import io.nexuspay.payment.domain.PaymentRequest;
import io.nexuspay.payment.domain.PaymentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B-029: billing must declare its screening rail + tenant through a TRUSTED {@link CallContext}
 * (SERVER_RECURRING, server-arg tenant), NOT through client-shaped metadata. Confirms the soft
 * recurring rail is granted via the trusted channel and that authority markers are no longer in
 * the request metadata.
 */
class PaymentOrchestrationAdapterTest {

    private PaymentGatewayPort port;
    private PaymentOrchestrationAdapter adapter;

    @BeforeEach
    void setUp() {
        port = mock(PaymentGatewayPort.class);
        adapter = new PaymentOrchestrationAdapter(port);
    }

    private static PaymentResponse ok() {
        return new PaymentResponse("pay_1", "succeeded", 5000, "USD", "automatic",
                "cust_1", "stripe", "txn_1", null, null, Instant.EPOCH, Map.of());
    }

    @Test
    void collectPayment_passesServerRecurringCallContext_withServerArgTenant() {
        when(port.createPayment(any(), any())).thenReturn(ok());

        adapter.collectPayment("tenant-T", "cust_1", "pm_1", 5000, "USD", "Invoice #9", "inv_9", true);

        ArgumentCaptor<CallContext> ctx = ArgumentCaptor.forClass(CallContext.class);
        ArgumentCaptor<PaymentRequest> req = ArgumentCaptor.forClass(PaymentRequest.class);
        org.mockito.Mockito.verify(port).createPayment(req.capture(), ctx.capture());

        assertThat(ctx.getValue().mode()).isEqualTo(ScreeningMode.SERVER_RECURRING);
        assertThat(ctx.getValue().tenantId()).isEqualTo("tenant-T");

        // Authority markers are no longer carried in metadata (the gate owns them); invoice_id stays.
        Map<String, Object> metadata = req.getValue().metadata();
        assertThat(metadata).doesNotContainKeys("tenant_id", "source");
        assertThat(metadata).containsEntry("invoice_id", "inv_9");
    }

    @Test
    void collectPayment_threadsDurableTestLiveModeIntoCallContext() {
        // DX-5a (MONEY-SAFETY): the subscription's durable is_live is carried into ctx.live() so the
        // gateway can route a TEST recurring charge to the mock on a SYSTEM thread (PaymentMode unset).
        when(port.createPayment(any(), any())).thenReturn(ok());

        // TEST subscription -> ctx.live()==FALSE
        adapter.collectPayment("tenant-T", "cust_1", "pm_1", 5000, "USD", "Invoice #9", "inv_9", false);
        ArgumentCaptor<CallContext> testCtx = ArgumentCaptor.forClass(CallContext.class);
        org.mockito.Mockito.verify(port).createPayment(any(), testCtx.capture());
        assertThat(testCtx.getValue().live()).isFalse();
        assertThat(testCtx.getValue().mode()).isEqualTo(ScreeningMode.SERVER_RECURRING);
    }

    @Test
    void collectPayment_liveSubscription_threadsLiveTrue() {
        when(port.createPayment(any(), any())).thenReturn(ok());

        adapter.collectPayment("tenant-T", "cust_1", "pm_1", 5000, "USD", "Invoice #9", "inv_9", true);
        ArgumentCaptor<CallContext> ctx = ArgumentCaptor.forClass(CallContext.class);
        org.mockito.Mockito.verify(port).createPayment(any(), ctx.capture());
        assertThat(ctx.getValue().live()).isTrue();
    }
}
