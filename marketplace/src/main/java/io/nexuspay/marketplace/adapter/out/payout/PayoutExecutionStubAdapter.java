package io.nexuspay.marketplace.adapter.out.payout;

import io.nexuspay.marketplace.application.port.out.PayoutExecutionPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Stub payout execution adapter for development and testing.
 * Real bank transfer / card push integration is tracked in GAP-062.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
@Component
public class PayoutExecutionStubAdapter implements PayoutExecutionPort {

    private static final Logger log = LoggerFactory.getLogger(PayoutExecutionStubAdapter.class);

    @Override
    public PayoutExecutionResult execute(PayoutExecutionRequest request) {
        String externalRef = "pex_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        log.info("Payout executed (stub): payoutId={}, account={}, amount={}{}, ref={}",
                request.payoutId(), request.connectedAccountId(),
                request.amount(), request.currency(), externalRef);

        return new PayoutExecutionResult(true, externalRef, null);
    }
}
