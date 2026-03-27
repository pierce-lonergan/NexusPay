package io.nexuspay.b2b.adapter.out.vendor;

import io.nexuspay.b2b.application.port.out.VendorPaymentExecutionPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Stub vendor payment execution adapter for development and testing.
 * Real provider integration (ACH/Nacha, SWIFT/wire, check printing) is tracked in GAP-067.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
@Component
public class VendorPaymentExecutionStubAdapter implements VendorPaymentExecutionPort {

    private static final Logger log = LoggerFactory.getLogger(VendorPaymentExecutionStubAdapter.class);

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
        String externalRef = "ref_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        log.info("Vendor payment executed (stub): paymentId={}, vendor={}, amount={} {}, method={}, ref={}",
                request.paymentId(), request.vendorId(), request.amount(),
                request.currency(), request.method(), externalRef);

        return new ExecutionResult(true, externalRef, null);
    }
}
