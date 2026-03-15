package io.nexuspay.billing.adapter.out.payment;

import io.nexuspay.billing.application.port.out.PaymentPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adapter that delegates payment collection to the payment-orchestration module.
 *
 * <p>In Phase 2, this is a stub implementation. Full integration with
 * payment-orchestration's {@code PaymentGatewayPort} happens when the
 * billing module is wired end-to-end with HyperSwitch.</p>
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
@Component
public class PaymentOrchestrationAdapter implements PaymentPort {

    private static final Logger log = LoggerFactory.getLogger(PaymentOrchestrationAdapter.class);

    @Override
    public PaymentResult collectPayment(String tenantId, String customerId,
                                         String paymentMethodId, long amount,
                                         String currency, String description,
                                         String invoiceId) {
        log.info("[STUB] Collecting payment: tenant={}, customer={}, amount={} {}, invoice={}",
                tenantId, customerId, amount, currency, invoiceId);

        // Stub: always succeeds in development
        String paymentId = "pi_stub_" + invoiceId;
        return PaymentResult.success(paymentId);
    }
}
