package io.nexuspay.billing.application.port.out;

/**
 * Output port for collecting payments on invoices.
 *
 * <p>Delegates to the payment-orchestration module to charge
 * a customer's payment method.</p>
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
public interface PaymentPort {

    /**
     * Attempts to collect payment for an invoice.
     *
     * @param tenantId         tenant context
     * @param customerId       customer to charge
     * @param paymentMethodId  stored payment method
     * @param amount           amount in minor units
     * @param currency         ISO 4217 currency code
     * @param description      charge description (shown on statement)
     * @param invoiceId        invoice reference for reconciliation
     * @return payment result with payment ID and success status
     */
    PaymentResult collectPayment(String tenantId, String customerId,
                                  String paymentMethodId, long amount,
                                  String currency, String description,
                                  String invoiceId);

    /**
     * Result of a payment collection attempt.
     */
    record PaymentResult(String paymentId, boolean success, String failureReason) {
        public static PaymentResult success(String paymentId) {
            return new PaymentResult(paymentId, true, null);
        }

        public static PaymentResult failure(String reason) {
            return new PaymentResult(null, false, reason);
        }
    }
}
