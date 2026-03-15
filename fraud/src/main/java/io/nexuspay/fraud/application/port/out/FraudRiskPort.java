package io.nexuspay.fraud.application.port.out;

import io.nexuspay.fraud.application.dto.PaymentContext;
import io.nexuspay.fraud.domain.model.RiskAssessment;

/**
 * Outbound port for external FRM (Fraud Risk Management) providers.
 * Each FRM provider implements this port (e.g., Sift, Signifyd).
 *
 * @since 0.3.0 (Sprint 3.1)
 */
public interface FraudRiskPort {

    /**
     * Sends payment context to the FRM provider and returns a risk assessment score.
     *
     * @param context payment and device information
     * @return risk score (0-100) from the provider
     */
    int assess(PaymentContext context);

    /**
     * Name of this FRM provider (e.g., "sift", "signifyd").
     */
    String providerName();

    /**
     * Priority in the fallback chain (lower = higher priority).
     */
    int priority();
}
