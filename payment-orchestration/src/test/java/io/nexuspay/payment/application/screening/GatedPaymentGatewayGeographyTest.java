package io.nexuspay.payment.application.screening;

import io.nexuspay.common.exception.PaymentException;
import io.nexuspay.fraud.application.port.in.AssessFraudRiskUseCase;
import io.nexuspay.fraud.domain.model.FraudAssessmentResult;
import io.nexuspay.fraud.domain.model.RiskDecision;
import io.nexuspay.payment.adapter.out.hyperswitch.HyperSwitchPaymentAdapter;
import io.nexuspay.payment.application.fx.CrossBorderComplianceService;
import io.nexuspay.payment.application.port.fx.CrossBorderCompliancePort;
import io.nexuspay.payment.application.port.fx.MerchantCurrencyPrefsRepository;
import io.nexuspay.payment.application.port.fx.MerchantCurrencyPrefsRepository.MerchantCurrencyPrefs;
import io.nexuspay.payment.domain.PaymentRequest;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.fx.CountryRestriction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-025 end-to-end through the @Primary {@link GatedPaymentGateway} with the REAL
 * {@link PreAuthorizationGate} + {@link ServerGeographyResolver} + {@link CrossBorderComplianceService}.
 * Only the leaf collaborators (PSP delegate, fraud engine, sanctions PORT, merchant repo, holds)
 * are mocked. This proves the omit/forge evasions cannot reach the PSP as a clean auto-capture.
 */
class GatedPaymentGatewayGeographyTest {

    private HyperSwitchPaymentAdapter delegate;
    private AssessFraudRiskUseCase fraud;
    private CrossBorderCompliancePort sanctionsPort;
    private MerchantCurrencyPrefsRepository merchantPrefs;
    private CaptureHoldService holds;
    private GatedPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        delegate = mock(HyperSwitchPaymentAdapter.class);
        fraud = mock(AssessFraudRiskUseCase.class);
        sanctionsPort = mock(CrossBorderCompliancePort.class);
        merchantPrefs = mock(MerchantCurrencyPrefsRepository.class);
        holds = mock(CaptureHoldService.class);

        // screen is healthy/available; nothing restricted unless a test says so
        lenient().when(sanctionsPort.isScreeningAvailable()).thenReturn(true);
        lenient().when(sanctionsPort.checkCountryRestriction(anyString())).thenReturn(Optional.empty());
        lenient().when(sanctionsPort.checkCountryRestriction(null)).thenReturn(Optional.empty());
        lenient().when(sanctionsPort.getRule(any(), any())).thenReturn(Optional.empty());
        lenient().when(sanctionsPort.requiresReporting(any(), any(), any(), any())).thenReturn(false);
        // fraud clean unless overridden
        lenient().when(fraud.assess(any())).thenReturn(new FraudAssessmentResult(
                UUID.randomUUID(), "idem-1", 1, RiskDecision.ALLOW, "internal", List.of(), List.of(), 0));

        CrossBorderComplianceService compliance = new CrossBorderComplianceService(sanctionsPort, true);
        ServerGeographyResolver resolver = new ServerGeographyResolver(merchantPrefs);
        PreAuthorizationGate gate = new PreAuthorizationGate(compliance, fraud, resolver);
        gateway = new GatedPaymentGateway(delegate, gate, holds);
    }

    private void merchantCountry(String tenantId, String country) {
        MerchantCurrencyPrefs p = new MerchantCurrencyPrefs(
                UUID.randomUUID(), tenantId, "USD", true, 0, "ECB", 15, country);
        when(merchantPrefs.findByTenantId(tenantId)).thenReturn(Optional.of(p));
    }

    private static PaymentRequest req(Map<String, Object> metadata) {
        return new PaymentRequest(5000, "USD", "cust_1", "card", "4111111111111111",
                null, "desc", "automatic", "idem-1", metadata);
    }

    private static PaymentResponse resp(String id, String captureMethod) {
        return new PaymentResponse(id, "requires_capture", 5000, "USD", captureMethod,
                "cust_1", "stripe", "txn_1", null, null, Instant.EPOCH, Map.of());
    }

    @Test
    void omitAttack_noClientGeography_unknownSource_routesToReview_notCleanAutoCapture() {
        // Tenant merchant_country=US (dest known), NO trusted edge source → cross-border-capable
        // with unknown source → REVIEW. Interactive flow → capture forced to manual + hold written.
        // The PSP must NOT receive a clean auto-capture.
        merchantCountry("t1", "US");
        when(delegate.createPayment(any())).thenReturn(resp("pay_1", "manual"));

        gateway.createPayment(req(Map.of("tenant_id", "t1"))); // no source/destination metadata

        ArgumentCaptor<PaymentRequest> sent = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(delegate).createPayment(sent.capture());
        assertThat(sent.getValue().captureMethod()).isEqualTo("manual"); // NOT auto-captured
        verify(holds).hold(eq("pay_1"), eq("t1"), any());
        // fraud is never consulted — the geo-review short-circuits before it
        verify(fraud, never()).assess(any());
    }

    @Test
    void forgeAttack_clientBenignDestination_serverSanctioned_isBlocked_pspNeverCalled() {
        // Client forges destination_country=US (benign) but server merchant_country=IR (sanctioned).
        // The SERVER value wins → hard block, PSP never called.
        merchantCountry("t1", "IR");
        when(sanctionsPort.checkCountryRestriction("IR")).thenReturn(Optional.of(new CountryRestriction(
                "IR", CountryRestriction.RestrictionType.SANCTIONED, "sanctioned")));

        assertThatThrownBy(() -> gateway.createPayment(
                req(Map.of("tenant_id", "t1", "destination_country", "US"))))
                .isInstanceOfSatisfying(PaymentException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("cross_border_blocked"));

        verify(delegate, never()).createPayment(any());
    }

    @Test
    void forgeAttack_clientCannotInflateToBlock_serverCleanUsWins_butStillReviewOnUnknownSource() {
        // Client claims destination IR (sanctioned) but server merchant_country=US (clean).
        // Server US wins → no hard block. Source unknown → still REVIEW (not a clean allow),
        // proving the client also cannot force a clean pass by lying either direction.
        merchantCountry("t1", "US");
        when(delegate.createPayment(any())).thenReturn(resp("pay_2", "manual"));

        gateway.createPayment(req(Map.of("tenant_id", "t1", "destination_country", "IR")));

        ArgumentCaptor<PaymentRequest> sent = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(delegate).createPayment(sent.capture());
        assertThat(sent.getValue().captureMethod()).isEqualTo("manual"); // REVIEW hold, not clean capture
    }

    @Test
    void screeningUnavailable_blocksEvenCleanFlow_pspNeverCalled() {
        merchantCountry("t1", "US");
        when(sanctionsPort.isScreeningAvailable()).thenReturn(false);

        assertThatThrownBy(() -> gateway.createPayment(req(Map.of("tenant_id", "t1"))))
                .isInstanceOfSatisfying(PaymentException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("cross_border_blocked"));

        verify(delegate, never()).createPayment(any());
    }

    @Test
    void domesticKnownFlow_withTrustedEdgeSource_allowsCleanAutoCapture() {
        // dest US (merchant) + trusted edge source US → domestic, both known → clean ALLOW,
        // auto-capture preserved, no hold. Proves the change does not over-block legitimate domestic.
        merchantCountry("t1", "US");
        when(delegate.createPayment(any())).thenReturn(resp("pay_ok", "automatic"));

        gateway.createPayment(req(Map.of("tenant_id", "t1",
                ServerGeographyResolver.TRUSTED_IP_COUNTRY_KEY, "US")));

        ArgumentCaptor<PaymentRequest> sent = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(delegate).createPayment(sent.capture());
        assertThat(sent.getValue().captureMethod()).isEqualTo("automatic"); // clean auto-capture
        verify(holds, never()).hold(any(), any(), any());
        verify(fraud).assess(any()); // domestic clean → fraud DID run
    }

    @Test
    void omitAttack_onServerRecurringRail_unknownGeo_isHeld_notCapturedClean() {
        // FIX 3 (OFAC blind spot): source=billing_subscription → SERVER_RECURRING. merchant_country=US
        // (dest known) but NO trusted edge source → cross-border-capable + unknown source → MANDATORY
        // COMPLIANCE REVIEW. Before the fix, the server rail discarded the unknown-geo hold and the
        // charge captured clean. Now it MUST be held (manual capture) + a hold written on EVERY rail.
        merchantCountry("t1", "US");
        when(delegate.createPayment(any())).thenReturn(resp("pay_srv", "manual"));

        gateway.createPayment(req(Map.of("tenant_id", "t1", "source", "billing_subscription")));

        ArgumentCaptor<PaymentRequest> sent = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(delegate).createPayment(sent.capture());
        assertThat(sent.getValue().captureMethod()).isEqualTo("manual"); // held, NOT clean auto-capture
        verify(holds).hold(eq("pay_srv"), eq("t1"), any());
    }

    @Test
    void omitAttack_onServerWorkflowRail_unknownGeo_isHeld_notCapturedClean() {
        // FIX 3: the SERVER_OTHER (workflow) rail must also hold the unknown-geo compliance review.
        merchantCountry("t1", "US");
        when(delegate.createPayment(any())).thenReturn(resp("pay_wf", "manual"));

        gateway.createPayment(req(Map.of("tenant_id", "t1", "workflow", "vendor_payout")));

        ArgumentCaptor<PaymentRequest> sent = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(delegate).createPayment(sent.capture());
        assertThat(sent.getValue().captureMethod()).isEqualTo("manual"); // held, NOT clean auto-capture
        verify(holds).hold(eq("pay_wf"), eq("t1"), any());
    }

    @Test
    void serverRail_fraudReview_withKnownGeo_stillCapturesClean_M1PolicyPreserved() {
        // Regression guard for FIX 3 scoping: an ORDINARY fraud REVIEW on a server rail (geography
        // fully known + domestic, so NO compliance mandatoryReview) must STILL capture clean — the
        // mandatoryReview marker must not leak into the fraud path and trip M1 dunning.
        merchantCountry("t1", "US");
        when(fraud.assess(any())).thenReturn(new FraudAssessmentResult(
                UUID.randomUUID(), "idem-1", 60, RiskDecision.REVIEW, "internal", List.of(), List.of(), 0));
        when(delegate.createPayment(any())).thenReturn(resp("pay_fr", "automatic"));

        gateway.createPayment(req(Map.of("tenant_id", "t1", "source", "billing_subscription",
                ServerGeographyResolver.TRUSTED_IP_COUNTRY_KEY, "US"))); // domestic known → fraud review only

        ArgumentCaptor<PaymentRequest> sent = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(delegate).createPayment(sent.capture());
        assertThat(sent.getValue().captureMethod()).isEqualTo("automatic"); // NOT held (M1 preserved)
        verify(holds, never()).hold(any(), any(), any());
    }

    @Test
    void confirmPath_serverRail_unknownGeo_onAutoCaptureIntent_isRefused() {
        // FIX 3 at confirm: a server-rail confirm of an AUTO-capture intent with unknown geography
        // must be REFUSED (compliance_review_hold), not allowed to confirm+capture clean.
        merchantCountry("t1", "US"); // dest known, no trusted source → unknown geo → mandatory review
        when(delegate.getPayment("pay_c")).thenReturn(new PaymentResponse(
                "pay_c", "requires_confirmation", 5000, "USD", "automatic",
                "cust_1", "stripe", "txn_1", null, null, Instant.EPOCH,
                Map.of("tenant_id", "t1", "source", "billing_subscription")));

        assertThatThrownBy(() -> gateway.confirmPayment("pay_c",
                new io.nexuspay.payment.domain.ConfirmRequest(null, null, null, "k")))
                .isInstanceOfSatisfying(PaymentException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("compliance_review_hold"));

        verify(holds).hold(eq("pay_c"), eq("t1"), any());
        verify(delegate, never()).confirmPayment(any(), any());
    }

    @Test
    void confirmPath_recomputesGeographyFromTrustedTenant_noBlindSpotInheritance() {
        // A stored intent whose create-time metadata had NO country: confirm must recompute from
        // the trusted tenant (merchant_country=IR sanctioned) and block — not inherit a clean
        // blind spot from reconstructForScreening.
        merchantCountry("t1", "IR");
        when(sanctionsPort.checkCountryRestriction("IR")).thenReturn(Optional.of(new CountryRestriction(
                "IR", CountryRestriction.RestrictionType.SANCTIONED, "sanctioned")));
        when(delegate.getPayment("pay_x")).thenReturn(new PaymentResponse(
                "pay_x", "requires_confirmation", 5000, "USD", "automatic",
                "cust_1", "stripe", "txn_1", null, null, Instant.EPOCH,
                Map.of("tenant_id", "t1"))); // no country in stored metadata

        assertThatThrownBy(() -> gateway.confirmPayment("pay_x",
                new io.nexuspay.payment.domain.ConfirmRequest(null, null, null, "k")))
                .isInstanceOfSatisfying(PaymentException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("cross_border_blocked"));

        verify(delegate, never()).confirmPayment(any(), any());
    }
}
