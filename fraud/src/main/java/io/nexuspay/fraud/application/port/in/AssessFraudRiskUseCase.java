package io.nexuspay.fraud.application.port.in;

import io.nexuspay.fraud.application.dto.PaymentContext;
import io.nexuspay.fraud.domain.model.FraudAssessmentResult;

/**
 * Inbound port for assessing fraud risk on a payment.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
public interface AssessFraudRiskUseCase {

    /**
     * Evaluates the payment context against active fraud rules and FRM providers.
     *
     * @param context payment and device information
     * @return assessment result with decision (ALLOW, REVIEW, BLOCK)
     */
    FraudAssessmentResult assess(PaymentContext context);
}
