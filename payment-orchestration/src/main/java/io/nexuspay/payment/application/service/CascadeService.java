package io.nexuspay.payment.application.service;

import io.nexuspay.payment.domain.routing.CascadeResult;
import io.nexuspay.payment.domain.routing.CascadeResult.CascadeAttempt;
import io.nexuspay.payment.domain.routing.CascadeResult.DeclineType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Automatic failover cascade service.
 * Attempts payment with PSPs in order from the routing decision.
 * On soft decline: retry with next PSP.
 * On hard decline: stop cascade, return decline.
 * Max cascade depth configurable per tenant (default 3).
 *
 * @since 0.3.0 (Sprint 3.3)
 */
@Service
public class CascadeService {

    private static final Logger LOG = LoggerFactory.getLogger(CascadeService.class);

    private final Set<String> softDeclineCodes;
    private final Set<String> hardDeclineCodes;

    public CascadeService(
            @Value("${nexuspay.routing.cascade.soft-decline-codes:DO_NOT_HONOR,INSUFFICIENT_FUNDS,ISSUER_UNAVAILABLE}")
            List<String> softDeclineCodes,
            @Value("${nexuspay.routing.cascade.hard-decline-codes:STOLEN_CARD,LOST_CARD,FRAUD,INVALID_CARD}")
            List<String> hardDeclineCodes) {
        this.softDeclineCodes = new HashSet<>(softDeclineCodes);
        this.hardDeclineCodes = new HashSet<>(hardDeclineCodes);
    }

    /**
     * Classifies a decline code as soft (retryable) or hard (terminal).
     */
    public DeclineType classifyDecline(String declineCode) {
        if (declineCode == null) return DeclineType.ERROR;
        String normalized = declineCode.toUpperCase().trim();
        if (hardDeclineCodes.contains(normalized)) return DeclineType.HARD;
        if (softDeclineCodes.contains(normalized)) return DeclineType.SOFT;
        // Unknown codes treated as soft declines (retry-safe default)
        return DeclineType.SOFT;
    }

    /**
     * Determines if a cascade should continue based on the decline type.
     *
     * @param declineType the type of decline from the previous attempt
     * @return true if the cascade should try the next PSP
     */
    public boolean shouldContinueCascade(DeclineType declineType) {
        return declineType == DeclineType.SOFT || declineType == DeclineType.TIMEOUT;
    }

    /**
     * Builds a cascade result from individual attempt outcomes.
     * Called by the payment orchestration layer as it executes each PSP attempt.
     */
    public CascadeResult buildResult(String paymentId, List<CascadeAttempt> attempts) {
        if (attempts.isEmpty()) {
            return CascadeResult.failure(paymentId, attempts);
        }

        CascadeAttempt lastAttempt = attempts.get(attempts.size() - 1);
        if (lastAttempt.succeeded()) {
            return CascadeResult.success(paymentId, lastAttempt.pspConnector(), attempts);
        } else {
            return CascadeResult.failure(paymentId, attempts);
        }
    }

    /**
     * Creates a cascade attempt record.
     */
    public CascadeAttempt createAttempt(String pspConnector, int attemptNumber,
                                         boolean succeeded, String declineCode, long latencyMs) {
        DeclineType declineType = succeeded ? null : classifyDecline(declineCode);
        return new CascadeAttempt(pspConnector, attemptNumber, succeeded, declineCode, declineType, latencyMs);
    }
}
