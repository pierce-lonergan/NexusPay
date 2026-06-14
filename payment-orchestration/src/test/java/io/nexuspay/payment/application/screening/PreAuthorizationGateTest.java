package io.nexuspay.payment.application.screening;

import io.nexuspay.common.exception.PaymentException;
import io.nexuspay.fraud.application.dto.PaymentContext;
import io.nexuspay.fraud.application.port.in.AssessFraudRiskUseCase;
import io.nexuspay.fraud.domain.model.FraudAssessmentResult;
import io.nexuspay.fraud.domain.model.RiskDecision;
import io.nexuspay.payment.application.fx.CrossBorderComplianceService;
import io.nexuspay.payment.application.fx.CrossBorderComplianceService.ComplianceResult;
import io.nexuspay.payment.application.screening.ServerGeographyResolver.ResolvedGeography;
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
    private ServerGeographyResolver geoResolver;
    private PreAuthorizationGate gate;

    @BeforeEach
    void setUp() {
        compliance = mock(CrossBorderComplianceService.class);
        fraudAssessor = mock(AssessFraudRiskUseCase.class);
        geoResolver = mock(ServerGeographyResolver.class);
        gate = new PreAuthorizationGate(compliance, fraudAssessor, geoResolver);
        // default: geography resolves to a known DOMESTIC US→US flow (not cross-border-capable),
        // so the unknown-geo REVIEW branch never fires unless a test overrides it.
        when(geoResolver.resolve(any(), any(), any()))
                .thenReturn(new ResolvedGeography("US", "US", true, true, List.of()));
        // default: compliance allows unless a test overrides it (5-arg geography-aware overload).
        when(compliance.validateOrThrow(any(), any(), any(), anyString(), any()))
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
        // Server resolves a sanctioned destination (merchant_country=KP); client signals are
        // irrelevant to the decision (B-025) — the compliance check throws on the SERVER value.
        when(geoResolver.resolve(any(), any(), any()))
                .thenReturn(new ResolvedGeography("KP", "US", true, true, List.of()));
        when(compliance.validateOrThrow(any(), any(), any(), anyString(), any()))
                .thenThrow(new PaymentException("Transaction to sanctioned country: KP", "cross_border_blocked"));

        var signals = new GateSignals("US", "US", null, null, null, null, null, null);
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
        verify(compliance).validateOrThrow(any(), any(), amount.capture(), anyString(), any());
        assertThat(amount.getValue()).isEqualByComparingTo("50.00");

        // JPY exponent 0: 5000 minor → 5000 major (the classic minor/major trap)
        gate.evaluate("idem-1", req(5000, "JPY"), "tenant_1", GateSignals.none());
        verify(compliance, org.mockito.Mockito.atLeastOnce())
                .validateOrThrow(any(), any(), amount.capture(), anyString(), any());
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

    // ---- B-024: server-rail screening modes ----

    @Test
    void block_onServerRecurringRail_downgradesToReview_doesNotThrow() {
        when(fraudAssessor.assess(any())).thenReturn(fraud(RiskDecision.BLOCK));

        GateDecision d = gate.evaluate("idem-1", req(5000, "USD"), "tenant_1",
                GateSignals.none(), ScreeningMode.SERVER_RECURRING);

        assertThat(d.holdCapture()).isTrue();                       // authorize + hold, never decline
        assertThat(d.fraudDecision()).isEqualTo(RiskDecision.REVIEW);
    }

    @Test
    void sanctioned_blocksEvenOnServerRail_fraudNotConsulted() {
        when(geoResolver.resolve(any(), any(), any()))
                .thenReturn(new ResolvedGeography("KP", "US", true, true, List.of()));
        when(compliance.validateOrThrow(any(), any(), any(), anyString(), any()))
                .thenThrow(new PaymentException("Transaction to sanctioned country: KP", "cross_border_blocked"));

        var signals = new GateSignals("US", "US", null, null, null, null, null, null);
        assertThatThrownBy(() -> gate.evaluate("idem-1", req(5000, "USD"), "t", signals, ScreeningMode.SERVER_RECURRING))
                .isInstanceOfSatisfying(PaymentException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("cross_border_blocked"));
        verifyNoInteractions(fraudAssessor);
    }

    @Test
    void fraudEngineError_interactive_failsLoud() {
        when(fraudAssessor.assess(any())).thenThrow(new RuntimeException("FRM down"));

        assertThatThrownBy(() -> gate.evaluate("idem-1", req(5000, "USD"), "t",
                GateSignals.none(), ScreeningMode.INTERACTIVE))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void fraudEngineError_serverRail_heldForReview_notThrown() {
        when(fraudAssessor.assess(any())).thenThrow(new RuntimeException("FRM down"));

        GateDecision d = gate.evaluate("idem-1", req(5000, "USD"), "t",
                GateSignals.none(), ScreeningMode.SERVER_RECURRING);

        assertThat(d.holdCapture()).isTrue();                       // FRM blip → hold, not decline-all
        assertThat(d.fraudDecision()).isEqualTo(RiskDecision.REVIEW);
        // FIX 3 scoping: a FRAUD-path hold is NOT a mandatory compliance review (so the M1
        // server-rail dunning policy still applies to it — only the geo branch forces an all-rail hold).
        assertThat(d.mandatoryReview()).isFalse();
    }

    // ---- B-025: unknown / forged geography fails closed to REVIEW (not silent ALLOW) ----

    @Test
    void unknownGeography_routesToReview_andNeverConsultsFraud_norAllows() {
        // Omit attack: client supplies NO usable geography; server cannot resolve a leg → the
        // compliance check returns a REVIEW outcome. The gate must hold capture, NOT auto-allow,
        // and must NOT fall through to fraud (the geo-review short-circuits first).
        when(geoResolver.resolve(any(), any(), any()))
                .thenReturn(new ResolvedGeography("US", null, true, false, List.of()));
        when(compliance.validateOrThrow(any(), any(), any(), anyString(), any()))
                .thenReturn(ComplianceResult.review(List.of("source_country_unknown",
                        CrossBorderComplianceService.GEO_UNKNOWN_REVIEW_FLAG)));

        GateDecision d = gate.evaluate("idem-1", req(5000, "USD"), "tenant_1", GateSignals.none());

        assertThat(d.holdCapture()).isTrue();                       // REVIEW = capture held, not ALLOW
        assertThat(d.fraudDecision()).isEqualTo(RiskDecision.REVIEW);
        assertThat(d.mandatoryReview()).isTrue();                   // FIX 3: compliance review marker set
        verifyNoInteractions(fraudAssessor);                        // geo-review short-circuits fraud
    }

    @Test
    void unknownGeography_onServerRail_stillReview_notAutoAllow() {
        // The unknown-geo REVIEW must survive server rails (it is NOT a fraud BLOCK, so
        // downgradesBlockToReview does not apply / cannot weaken it to ALLOW).
        when(geoResolver.resolve(any(), any(), any()))
                .thenReturn(new ResolvedGeography(null, null, false, false, List.of()));
        when(compliance.validateOrThrow(any(), any(), any(), anyString(), any()))
                .thenReturn(ComplianceResult.review(List.of(
                        CrossBorderComplianceService.GEO_UNKNOWN_REVIEW_FLAG)));

        GateDecision d = gate.evaluate("idem-1", req(5000, "USD"), "t",
                GateSignals.none(), ScreeningMode.SERVER_RECURRING);

        assertThat(d.fraudDecision()).isEqualTo(RiskDecision.REVIEW);
        assertThat(d.holdCapture()).isTrue();
        assertThat(d.mandatoryReview()).isTrue();                   // FIX 3: must hold on server rail too
        verifyNoInteractions(fraudAssessor);
    }

    @Test
    void screeningUnavailable_failsClosed_blocks_evenForCleanCountry() {
        // B-026 end-to-end through the gate: when the screen is down, compliance throws
        // cross_border_blocked — even for a clean country. The gate must propagate it.
        when(geoResolver.resolve(any(), any(), any()))
                .thenReturn(new ResolvedGeography("US", "US", true, true, List.of()));
        when(compliance.validateOrThrow(any(), any(), any(), anyString(), any()))
                .thenThrow(new PaymentException(
                        "Sanctions screening is temporarily unavailable", "cross_border_blocked"));

        assertThatThrownBy(() -> gate.evaluate("idem-1", req(5000, "USD"), "tenant_1", GateSignals.none()))
                .isInstanceOfSatisfying(PaymentException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("cross_border_blocked"));
        verifyNoInteractions(fraudAssessor);
    }

    @Test
    void gate_passesResolvedServerGeography_toCompliance_notClientSignals() {
        // The gate must hand the COMPLIANCE check the SERVER-resolved geography, never the
        // client signals. Client says DE→FR; server resolves US destination → US wins.
        when(geoResolver.resolve(any(), any(), any()))
                .thenReturn(new ResolvedGeography("US", "GB", true, true, List.of()));
        when(fraudAssessor.assess(any())).thenReturn(fraud(RiskDecision.ALLOW));
        ArgumentCaptor<String> src = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dest = ArgumentCaptor.forClass(String.class);

        var clientSignals = new GateSignals("DE", "FR", null, "DE", null, null, null, null);
        gate.evaluate("idem-1", req(5000, "USD"), "tenant_1", clientSignals);

        verify(compliance).validateOrThrow(src.capture(), dest.capture(), any(), anyString(), any());
        assertThat(src.getValue()).isEqualTo("GB");    // server source, not client "DE"
        assertThat(dest.getValue()).isEqualTo("US");   // server dest,  not client "FR"
    }
}
