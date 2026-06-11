package io.nexuspay.gateway.application;

import io.nexuspay.common.exception.PaymentException;
import io.nexuspay.fraud.application.dto.PaymentContext;
import io.nexuspay.fraud.application.port.in.AssessFraudRiskUseCase;
import io.nexuspay.fraud.domain.model.FraudAssessmentResult;
import io.nexuspay.fraud.domain.model.RiskDecision;
import io.nexuspay.payment.application.fx.CrossBorderComplianceService;
import io.nexuspay.payment.application.fx.CrossBorderComplianceService.ComplianceResult;
import io.nexuspay.payment.domain.PaymentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the B-003 pre-authorization gate: the security contract that
 * sanctions / fraud BLOCK reject the payment (no PSP call) and a fraud REVIEW
 * authorizes with capture held.
 */
class PreAuthorizationGateTest {

    private CrossBorderComplianceService compliance;
    private AssessFraudRiskUseCase fraudAssessor;
    private PreAuthorizationGate gate;

    @BeforeEach
    void setUp() {
        compliance = mock(CrossBorderComplianceService.class);
        fraudAssessor = mock(AssessFraudRiskUseCase.class);
        gate = new PreAuthorizationGate(compliance, fraudAssessor);
        // default: compliance allows unless a test overrides it
        when(compliance.validateOrThrow(any(), any(), any(), anyString()))
                .thenReturn(new ComplianceResult(true, false, false, List.of(), null));
    }

    private static PaymentRequest req(long amount, String currency) {
        return new PaymentRequest(amount, currency, "cust_1", "card", "4111111111111111",
                null, "desc", "automatic", "idem-1", Map.of());
    }

    private static FraudAssessmentResult fraud(RiskDecision decision) {
        return new FraudAssessmentResult(UUID.randomUUID(), "idem-1", 42, decision,
                "internal", List.of(), List.of(), 5);
    }

    @Test
    void allow_proceeds_withoutHoldingCapture() {
        when(fraudAssessor.assess(any())).thenReturn(fraud(RiskDecision.ALLOW));

        GateDecision d = gate.evaluate("idem-1", req(5000, "USD"), "tenant_1", GateSignals.none());

        assertThat(d.holdCapture()).isFalse();
        assertThat(d.fraudDecision()).isEqualTo(RiskDecision.ALLOW);
    }

    @Test
    void review_authorizesButHoldsCapture() {
        when(fraudAssessor.assess(any())).thenReturn(fraud(RiskDecision.REVIEW));

        GateDecision d = gate.evaluate("idem-1", req(5000, "USD"), "tenant_1", GateSignals.none());

        assertThat(d.holdCapture()).isTrue();
        assertThat(d.fraudDecision()).isEqualTo(RiskDecision.REVIEW);
    }

    @Test
    void block_rejectsWithFraudBlocked() {
        when(fraudAssessor.assess(any())).thenReturn(fraud(RiskDecision.BLOCK));

        assertThatThrownBy(() -> gate.evaluate("idem-1", req(5000, "USD"), "tenant_1", GateSignals.none()))
                .isInstanceOfSatisfying(PaymentException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("fraud_blocked"));
    }

    @Test
    void sanctionedCountry_rejects_andNeverReachesFraud() {
        when(compliance.validateOrThrow(any(), any(), any(), anyString()))
                .thenThrow(new PaymentException("Transaction to sanctioned country: KP", "cross_border_blocked"));

        var signals = new GateSignals("US", "KP", null, null, null, null, null, null);
        assertThatThrownBy(() -> gate.evaluate("idem-1", req(5000, "USD"), "tenant_1", signals))
                .isInstanceOfSatisfying(PaymentException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("cross_border_blocked"));

        // sanctions is the FIRST gate — fraud must not even be consulted
        verifyNoInteractions(fraudAssessor);
    }

    @Test
    void compliance_receivesAmountInMajorUnits_perCurrencyExponent() {
        when(fraudAssessor.assess(any())).thenReturn(fraud(RiskDecision.ALLOW));
        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);

        // USD exponent 2: 5000 minor → 50.00 major
        gate.evaluate("idem-1", req(5000, "USD"), "tenant_1", GateSignals.none());
        verify(compliance).validateOrThrow(any(), any(), amount.capture(), anyString());
        assertThat(amount.getValue()).isEqualByComparingTo("50.00");

        // JPY exponent 0: 5000 minor → 5000 major (the classic minor/major trap)
        gate.evaluate("idem-1", req(5000, "JPY"), "tenant_1", GateSignals.none());
        verify(compliance, org.mockito.Mockito.atLeastOnce())
                .validateOrThrow(any(), any(), amount.capture(), anyString());
        assertThat(amount.getValue()).isEqualByComparingTo("5000");
    }

    @Test
    void fraudContext_carriesTenantAmountAndCurrency() {
        when(fraudAssessor.assess(any())).thenReturn(fraud(RiskDecision.ALLOW));
        ArgumentCaptor<PaymentContext> ctx = ArgumentCaptor.forClass(PaymentContext.class);

        gate.evaluate("idem-1", req(7500, "USD"), "tenant_9", GateSignals.none());

        verify(fraudAssessor).assess(ctx.capture());
        assertThat(ctx.getValue().tenantId()).isEqualTo("tenant_9");
        assertThat(ctx.getValue().amountMinorUnits()).isEqualTo(7500);   // fraud uses minor units
        assertThat(ctx.getValue().currency()).isEqualTo("USD");
        assertThat(ctx.getValue().paymentId()).isEqualTo("idem-1");
    }

    @Test
    void blankPaymentRef_getsGeneratedReference_notNull() {
        when(fraudAssessor.assess(any())).thenReturn(fraud(RiskDecision.ALLOW));
        ArgumentCaptor<PaymentContext> ctx = ArgumentCaptor.forClass(PaymentContext.class);

        gate.evaluate(null, req(100, "USD"), "tenant_1", GateSignals.none());

        verify(fraudAssessor).assess(ctx.capture());
        assertThat(ctx.getValue().paymentId()).startsWith("preauth-");
    }
}
