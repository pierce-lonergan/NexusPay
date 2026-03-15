package io.nexuspay.billing.adapter.out.payment;

import io.nexuspay.billing.application.port.out.PaymentPort;
import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.domain.PaymentRequest;
import io.nexuspay.payment.domain.PaymentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Adapter that delegates payment collection to the payment-orchestration module
 * via {@link PaymentGatewayPort}, which in turn calls HyperSwitch.
 *
 * <p>This replaces the Sprint 2.5a stub adapter with a real integration.
 * Payments are created with {@code captureMethod=automatic} so funds are
 * captured immediately (no manual capture step for subscriptions).</p>
 *
 * @since 0.2.5b (Sprint 2.5b) — replaces stub from Sprint 2.5a
 */
@Component
public class PaymentOrchestrationAdapter implements PaymentPort {

    private static final Logger log = LoggerFactory.getLogger(PaymentOrchestrationAdapter.class);

    private final PaymentGatewayPort paymentGatewayPort;

    public PaymentOrchestrationAdapter(PaymentGatewayPort paymentGatewayPort) {
        this.paymentGatewayPort = paymentGatewayPort;
    }

    @Override
    public PaymentResult collectPayment(String tenantId, String customerId,
                                         String paymentMethodId, long amount,
                                         String currency, String description,
                                         String invoiceId) {
        log.info("Collecting payment via HyperSwitch: tenant={}, customer={}, amount={} {}, invoice={}",
                tenantId, customerId, amount, currency, invoiceId);

        try {
            String idempotencyKey = "billing_" + invoiceId + "_" + System.currentTimeMillis();

            PaymentRequest request = new PaymentRequest(
                    amount,
                    currency,
                    customerId,
                    "card",                   // Default to card payments for subscriptions
                    paymentMethodId,          // Payment method token/ID
                    null,                     // No return URL for server-to-server
                    description,
                    "automatic",              // Auto-capture for subscriptions
                    idempotencyKey,
                    Map.of(
                            "invoice_id", invoiceId,
                            "tenant_id", tenantId,
                            "source", "billing_subscription"
                    )
            );

            PaymentResponse response = paymentGatewayPort.createPayment(request);

            if (response.isSuccessful()) {
                log.info("Payment collected successfully: paymentId={}, invoice={}",
                        response.gatewayPaymentId(), invoiceId);
                return PaymentResult.success(response.gatewayPaymentId());
            } else if (response.isFailed()) {
                String reason = response.errorMessage() != null
                        ? response.errorMessage()
                        : "Payment failed with status: " + response.status();
                log.warn("Payment collection failed: invoice={}, reason={}", invoiceId, reason);
                return PaymentResult.failure(reason);
            } else {
                // Processing or requires additional steps — treat as pending/failed for dunning
                log.warn("Payment in intermediate state: invoice={}, status={}",
                        invoiceId, response.status());
                return PaymentResult.failure("Payment pending: " + response.status());
            }

        } catch (Exception e) {
            log.error("Payment collection error: invoice={}, error={}", invoiceId, e.getMessage(), e);
            return PaymentResult.failure("Payment system error: " + e.getMessage());
        }
    }
}
