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
        // SEC-25: derive the externalReference DETERMINISTICALLY from the idempotency key so a
        // re-drive of the SAME payout returns the SAME reference — modelling a PSP that dedups on
        // Idempotency-Key (B-009). Previously this returned a random pex_<uuid> per call, which would
        // have masked a double-pay in the reconciler (each re-drive looking like a fresh disbursement).
        // Fall back to a random ref only if no key is supplied (defensive; the production paths always
        // set one via Payout.idempotencyKey). The real GAP-062 adapter forwards the key to the PSP.
        String key = request.idempotencyKey();
        String externalRef = (key != null && !key.isBlank())
                ? "pex_" + Integer.toHexString(key.hashCode()) + key.substring(Math.max(0, key.length() - 8))
                : "pex_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        log.info("Payout executed (stub): payoutId={}, account={}, amount={}{}, key={}, ref={}",
                request.payoutId(), request.connectedAccountId(),
                request.amount(), request.currency(), key, externalRef);

        return new PayoutExecutionResult(true, externalRef, null);
    }
}
