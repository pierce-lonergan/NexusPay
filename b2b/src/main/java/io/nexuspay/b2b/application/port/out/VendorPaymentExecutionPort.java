package io.nexuspay.b2b.application.port.out;

import io.nexuspay.b2b.domain.VendorPaymentMethod;

/**
 * Outbound port for executing vendor payment disbursements.
 * Abstracts over ACH, wire, virtual card, and check payment rails.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
public interface VendorPaymentExecutionPort {

    ExecutionResult execute(ExecutionRequest request);

    record ExecutionRequest(
            String paymentId,
            String vendorId,
            long amount,
            String currency,
            VendorPaymentMethod method,
            String remittanceInfo
    ) {}

    record ExecutionResult(
            boolean success,
            String externalReference,
            String failureReason
    ) {}
}
