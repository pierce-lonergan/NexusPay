package io.nexuspay.gateway.application;

import io.nexuspay.fraud.application.dto.PaymentContext;
import io.nexuspay.fraud.application.port.in.AssessFraudRiskUseCase;
import io.nexuspay.fraud.domain.model.FraudAssessmentResult;
import io.nexuspay.payment.application.fx.CrossBorderComplianceService;
import io.nexuspay.payment.application.fx.CrossBorderComplianceService.ComplianceResult;
import io.nexuspay.payment.domain.PaymentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Map;
import java.util.UUID;

/**
 * Synchronous pre-authorization gate (B-003). Runs the cross-border compliance
 * check and the fraud risk assessment <em>before</em> a payment is sent to the PSP,
 * wiring two previously-unwired protective modules (a fraud BLOCK / a sanctioned
 * country was processed normally before this):
 *
 * <ul>
 *   <li>sanctioned source/destination country → reject ({@code cross_border_blocked});
 *   <li>fraud BLOCK → reject ({@code fraud_blocked}), no PSP call;
 *   <li>fraud REVIEW → allow the authorization but HOLD capture (manual capture);
 *   <li>ALLOW → proceed.
 * </ul>
 *
 * <p>Rejections are signalled by throwing {@code PaymentException} (mapped to 403 /
 * 422 by the gateway's exception handler), so the gate is fail-loud: a thrown
 * collaborator error surfaces as a 5xx rather than silently letting a payment
 * through.</p>
 *
 * <p><b>SCOPE / KNOWN LIMITATIONS (first cut — T3 review, tracked follow-ups).</b>
 * This gate sits on the interactive REST {@code POST /v1/payments} create path only;
 * it is NOT yet a complete control:
 * <ul>
 *   <li><b>Coverage (B-024):</b> {@code confirm}/{@code capture} and the sibling PSP
 *       callers (billing renewals, B2B vendor payouts, workflow activities) call the
 *       payment port directly and are not gated. A port-boundary placement is needed
 *       to cover all money-movement entrypoints.
 *   <li><b>Sanctions inputs (B-025):</b> source/destination country come from
 *       client-supplied request metadata; a caller can omit/forge them to evade the
 *       sanctions check. Authoritative geography (merchant config, geo-IP, BIN→issuer
 *       country) must drive the OFAC screen before this is relied on as a sanctions
 *       control.
 *   <li><b>OFAC list (B-026):</b> the underlying sanctions adapter currently
 *       degrades to a small static country list; the screen is fraud-grade, not
 *       comprehensive OFAC, until that is fixed and made fail-closed.
 *   <li><b>REVIEW enforcement (B-027):</b> the capture-hold is the PSP
 *       {@code capture_method=manual} flag only; it is not yet persisted/enforced at
 *       the capture endpoint, and the assessment is not linked to the final payment id.
 * </ul>
 *
 * @since 0.4.0 (B-003)
 */
@Service
public class PreAuthorizationGate {

    private static final Logger log = LoggerFactory.getLogger(PreAuthorizationGate.class);

    private final CrossBorderComplianceService compliance;
    private final AssessFraudRiskUseCase fraudAssessor;

    public PreAuthorizationGate(CrossBorderComplianceService compliance,
                                AssessFraudRiskUseCase fraudAssessor) {
        this.compliance = compliance;
        this.fraudAssessor = fraudAssessor;
    }

    /**
     * Evaluates a payment about to be created. Returns a {@link GateDecision} for
     * ALLOW/REVIEW; throws {@code PaymentException} for a sanctioned country
     * ({@code cross_border_blocked}) or a fraud BLOCK ({@code fraud_blocked}).
     *
     * @param paymentRef a stable reference for the (not-yet-created) payment used to
     *                   key the fraud assessment — the idempotency key when present
     * @param req        the domain payment request
     * @param tenantId   the caller's tenant (from the authenticated principal)
     * @param signals    geography/card/device signals derived from the request
     */
    public GateDecision evaluate(String paymentRef, PaymentRequest req, String tenantId, GateSignals signals) {
        GateSignals s = signals != null ? signals : GateSignals.none();
        String ref = (paymentRef != null && !paymentRef.isBlank())
                ? paymentRef
                : "preauth-" + UUID.randomUUID();

        // 1. Cross-border compliance / sanctions. Throws cross_border_blocked on a
        //    sanctioned source or destination country (the PSP is never called).
        ComplianceResult cb = compliance.validateOrThrow(
                s.sourceCountry(), s.destinationCountry(),
                toMajorUnits(req.amount(), req.currency()), req.currency());

        // 2. Fraud risk assessment.
        PaymentContext ctx = new PaymentContext(
                ref,
                tenantId,
                req.amount(),
                req.currency(),
                req.customerId(),
                s.customerEmail(),
                s.cardBin(),
                s.cardHash(),
                s.ipAddress(),
                s.ipCountry(),
                s.deviceFingerprintHash(),
                Map.of(),
                req.metadata() == null ? Map.of() : req.metadata());

        FraudAssessmentResult fraud = fraudAssessor.assess(ctx);

        switch (fraud.decision()) {
            case BLOCK -> {
                log.warn("Payment {} BLOCKED by fraud assessment (score={}, rules={})",
                        ref, fraud.aggregatedScore(), fraud.triggeredRuleIds());
                // PaymentException is (message, errorCode).
                throw new io.nexuspay.common.exception.PaymentException(
                        "Payment blocked by fraud risk assessment", "fraud_blocked");
            }
            case REVIEW -> {
                log.info("Payment {} flagged for REVIEW (score={}) — authorizing with capture held",
                        ref, fraud.aggregatedScore());
                return new GateDecision(true, fraud.decision(), fraud.assessmentId(),
                        cb.requiresReporting(), cb.requiresEnhancedDueDiligence());
            }
            default -> {
                return new GateDecision(false, fraud.decision(), fraud.assessmentId(),
                        cb.requiresReporting(), cb.requiresEnhancedDueDiligence());
            }
        }
    }

    /**
     * Convert minor units (the storage/transport unit) to a major-unit amount for
     * the compliance reporting-threshold check, using the currency's exponent
     * (USD→2, JPY→0, BHD→3). Mirrors the CurrencyMath discipline from B-014a so a
     * JPY 1000 is not mistaken for 10.00. Unknown currencies fall back to 2.
     */
    private static BigDecimal toMajorUnits(long minor, String currency) {
        int fraction = 2;
        try {
            int f = Currency.getInstance(currency).getDefaultFractionDigits();
            if (f >= 0) fraction = f;
        } catch (RuntimeException ignored) {
            // unknown/invalid currency code — keep the default exponent
        }
        return BigDecimal.valueOf(minor).movePointLeft(fraction);
    }
}
