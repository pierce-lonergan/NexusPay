package io.nexuspay.reconciliation.application.port.out;

import java.util.Optional;

/**
 * Port for querying payment records during reconciliation matching.
 *
 * <p>Adapters may query NexusPay's own payment views or call HyperSwitch
 * directly depending on the data source configuration.</p>
 *
 * @since 0.2.0 (Sprint 2.3)
 */
public interface PaymentQueryPort {

    /**
     * Finds a payment by its external (PSP) reference ID and provider.
     *
     * @param externalRef the PSP's payment/transaction ID
     * @param provider    the PSP provider name (e.g., "stripe", "hyperswitch")
     * @return payment record if found
     */
    Optional<PaymentRecord> findByExternalRef(String externalRef, String provider);

    /**
     * Lightweight DTO for payment data needed during reconciliation matching.
     */
    record PaymentRecord(
            String paymentId,
            String externalPaymentId,
            long amount,
            String currency,
            String status,
            String provider
    ) {}
}
