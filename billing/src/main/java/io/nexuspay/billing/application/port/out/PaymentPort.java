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
     * <p>DX-5a (MONEY-SAFETY): {@code live} is the owning subscription's DURABLE test/live mode
     * ({@code subscriptions.is_live}). Renewals/dunning run on a {@code @Scheduled
     * @SystemTransactional} SYSTEM thread where the request-scoped {@code PaymentMode} is unset, so the
     * caller MUST pass the durable flag explicitly; the adapter threads it into the gateway
     * {@code CallContext} so a TEST subscription's recurring charge routes to the mock — never the
     * real PSP.</p>
     *
     * @param tenantId         tenant context
     * @param customerId       customer to charge
     * @param paymentMethodId  stored payment method
     * @param amount           amount in minor units
     * @param currency         ISO 4217 currency code
     * @param description      charge description (shown on statement)
     * @param invoiceId        invoice reference for reconciliation
     * @param live             DX-5a durable test/live mode of the owning subscription (true=live PSP,
     *                         false=test mock)
     * @return payment result with payment ID and success status
     */
    PaymentResult collectPayment(String tenantId, String customerId,
                                  String paymentMethodId, long amount,
                                  String currency, String description,
                                  String invoiceId, boolean live);

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
