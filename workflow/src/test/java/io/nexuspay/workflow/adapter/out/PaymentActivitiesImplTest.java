package io.nexuspay.workflow.adapter.out;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.payment.adapter.out.outbox.OutboxEventRepository;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.application.screening.CallContext;
import io.nexuspay.payment.application.screening.ScreeningMode;
import io.nexuspay.payment.domain.PaymentRequest;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.workflow.application.PaymentWorkflowRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-029: the Temporal payment activity must declare its screening rail (SERVER_OTHER) + the TRUSTED
 * tenant (threaded from the workflow request) via a {@link CallContext}, NOT via a client-shaped
 * "workflow" metadata marker — and the gate must now receive a real tenant on the workflow path.
 */
class PaymentActivitiesImplTest {

    private PaymentGatewayPort gateway;
    private PaymentActivitiesImpl activities;

    @BeforeEach
    void setUp() {
        gateway = mock(PaymentGatewayPort.class);
        OutboxEventRepository outbox = mock(OutboxEventRepository.class);
        activities = new PaymentActivitiesImpl(gateway, outbox, new ObjectMapper());
    }

    private static PaymentResponse ok() {
        return new PaymentResponse("ext_1", "succeeded", 5000, "USD", "automatic",
                null, "stripe", "txn_1", null, null, Instant.EPOCH, Map.of());
    }

    @Test
    void createPayment_passesServerOtherCallContext_withWorkflowTenant() {
        when(gateway.createPayment(any(), any())).thenReturn(ok());

        activities.createPayment(new PaymentWorkflowRequest(
                "pay_1", 5000, "USD", "card", "tenant-T", "idem-1"));

        ArgumentCaptor<CallContext> ctx = ArgumentCaptor.forClass(CallContext.class);
        ArgumentCaptor<PaymentRequest> req = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(gateway).createPayment(req.capture(), ctx.capture());

        assertThat(ctx.getValue().mode()).isEqualTo(ScreeningMode.SERVER_OTHER);
        assertThat(ctx.getValue().tenantId()).isEqualTo("tenant-T"); // threaded from the workflow request

        // The "workflow" authority marker is no longer carried in metadata (the gate owns the rail).
        assertThat(req.getValue().metadata()).doesNotContainKey("workflow");
        assertThat(req.getValue().metadata()).containsEntry("nexuspay_payment_id", "pay_1");
    }
}
