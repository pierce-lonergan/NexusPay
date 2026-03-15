package io.nexuspay.workflow.adapter.out;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.payment.adapter.out.outbox.OutboxEvent;
import io.nexuspay.payment.adapter.out.outbox.OutboxEventRepository;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.domain.PaymentRequest;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.VoidRequest;
import io.nexuspay.workflow.application.PaymentActivities;
import io.nexuspay.workflow.application.PaymentWorkflowRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Implementation of Temporal payment activities.
 *
 * <p>Each activity method is a single unit of work that Temporal records
 * in its event history. If the worker crashes mid-activity, Temporal retries
 * the activity (not the whole workflow) according to the retry policy.</p>
 *
 * <p>This class is a Spring-managed bean — it can inject ports and adapters.
 * It is registered with the Temporal worker as an <em>instance</em>, not a class.</p>
 *
 * @since 0.2.0 (Sprint 2.2)
 */
@Component
public class PaymentActivitiesImpl implements PaymentActivities {

    private static final Logger log = LoggerFactory.getLogger(PaymentActivitiesImpl.class);

    private final PaymentGatewayPort paymentGateway;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public PaymentActivitiesImpl(PaymentGatewayPort paymentGateway,
                                 OutboxEventRepository outboxRepository,
                                 ObjectMapper objectMapper) {
        this.paymentGateway = paymentGateway;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String createPayment(PaymentWorkflowRequest request) {
        log.info("Activity: Creating payment in HyperSwitch: paymentId={}, amount={} {}",
                request.paymentId(), request.amountInMinorUnits(), request.currency());

        PaymentRequest gatewayRequest = new PaymentRequest(
                request.amountInMinorUnits(),
                request.currency(),
                null,                           // customerId — resolved upstream
                request.paymentMethod(),
                null,                           // paymentMethodData — resolved upstream
                null,                           // returnUrl
                "Workflow payment " + request.paymentId(),
                "automatic",                    // captureMethod
                request.idempotencyKey(),
                Map.of("nexuspay_payment_id", request.paymentId(),
                       "workflow", "payment_with_retry")
        );

        PaymentResponse response = paymentGateway.createPayment(gatewayRequest);

        log.info("Activity: Payment created in HyperSwitch: paymentId={}, externalId={}, status={}",
                request.paymentId(), response.gatewayPaymentId(), response.status());

        return response.gatewayPaymentId();
    }

    @Override
    public String confirmPayment(String externalPaymentId) {
        log.info("Activity: Confirming payment in HyperSwitch: externalId={}", externalPaymentId);

        PaymentResponse response = paymentGateway.getPayment(externalPaymentId);

        log.info("Activity: Payment status: externalId={}, status={}",
                externalPaymentId, response.status());

        return response.status();
    }

    @Override
    @Transactional
    public void publishPaymentEvent(String paymentId, String externalPaymentId,
                                     String status, String tenantId) {
        log.info("Activity: Publishing payment event to outbox: paymentId={}, status={}",
                paymentId, status);

        String eventType = "Payment" + capitalize(status.toLowerCase());

        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "payment_id", paymentId,
                    "external_payment_id", externalPaymentId,
                    "status", status,
                    "timestamp", Instant.now().toString()
            ));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize payment event payload", e);
        }

        OutboxEvent outboxEvent = new OutboxEvent(
                "Payment",
                paymentId,
                eventType,
                payload,
                tenantId,
                1
        );

        outboxRepository.save(outboxEvent);

        log.info("Activity: Payment event written to outbox: paymentId={}, eventType={}",
                paymentId, eventType);
    }

    @Override
    public void voidPayment(String externalPaymentId) {
        log.info("Activity: Voiding payment in HyperSwitch: externalId={}", externalPaymentId);

        paymentGateway.voidPayment(externalPaymentId, new VoidRequest(
                "Workflow cancellation or timeout",
                null
        ));

        log.info("Activity: Payment voided: externalId={}", externalPaymentId);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
