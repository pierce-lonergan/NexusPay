package io.nexuspay.fraud.application.service;

import io.nexuspay.fraud.application.dto.PaymentContext;
import io.nexuspay.fraud.application.port.out.FraudRiskPort;
import io.nexuspay.fraud.config.FraudProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Implements fallback chain across FRM providers.
 *
 * <p>Try primary FRM → on failure, try secondary → on failure,
 * fall back to native rules only (returning null FRM score).</p>
 *
 * <p>Each provider call is wrapped with a circuit breaker configured
 * in the provider adapter. The chain respects priority ordering.</p>
 *
 * @since 0.3.0 (Sprint 3.1)
 */
@Component
public class FallbackFraudChainService {

    private static final Logger log = LoggerFactory.getLogger(FallbackFraudChainService.class);

    private final List<FraudRiskPort> providers;
    private final FraudProperties fraudProperties;

    public FallbackFraudChainService(List<FraudRiskPort> providers,
                                      FraudProperties fraudProperties) {
        this.providers = providers.stream()
                .sorted(Comparator.comparingInt(FraudRiskPort::priority))
                .toList();
        this.fraudProperties = fraudProperties;
    }

    /**
     * Attempts FRM assessment through the provider chain.
     *
     * @param context payment context
     * @return FRM result with score and provider name, or null score if all fail
     */
    public FrmResult assessWithFallback(PaymentContext context) {
        for (FraudRiskPort provider : providers) {
            try {
                int score = provider.assess(context);
                log.debug("FRM provider {} returned score {} for payment {}",
                        provider.providerName(), score, context.paymentId());
                return new FrmResult(score, provider.providerName());
            } catch (Exception e) {
                log.warn("FRM provider {} failed for payment {}: {}",
                        provider.providerName(), context.paymentId(), e.getMessage());
            }
        }

        log.warn("All FRM providers failed for payment {}; falling back to native-only scoring",
                context.paymentId());
        return new FrmResult(null, "NATIVE_ONLY");
    }

    /**
     * Result from the FRM fallback chain.
     *
     * @param score    FRM score (0-100), or null if all providers failed
     * @param provider name of the provider that responded, or "NATIVE_ONLY"
     */
    public record FrmResult(Integer score, String provider) {}
}
