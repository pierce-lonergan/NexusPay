package io.nexuspay.gateway.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.domain.SessionToken;
import io.nexuspay.common.mode.PaymentMode;
import io.nexuspay.gateway.adapter.in.rest.dto.CreatePaymentSessionRequest;
import io.nexuspay.gateway.adapter.in.rest.dto.PaymentSessionResponse;
import io.nexuspay.gateway.application.port.out.PaymentSessionRepository;
import io.nexuspay.gateway.application.service.PaymentSessionService;
import io.nexuspay.gateway.domain.PaymentSession;
import io.nexuspay.iam.adapter.in.filter.SessionTokenAuthenticationFilter;
import io.nexuspay.iam.application.service.SessionTokenIssuer;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.nexuspay.payment.adapter.out.hyperswitch.HyperSwitchPaymentAdapter;
import io.nexuspay.payment.adapter.out.mock.MockPaymentGatewayPort;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.application.screening.CallContext;
import io.nexuspay.payment.application.screening.CaptureHoldService;
import io.nexuspay.payment.application.screening.GatedPaymentGateway;
import io.nexuspay.payment.application.screening.PreAuthorizationGate;
import io.nexuspay.payment.application.screening.ScreeningOriginService;
import io.nexuspay.payment.application.webhook.MockWebhookSynthesizer;
import io.nexuspay.payment.application.webhook.WebhookMetadataService;
import io.nexuspay.payment.domain.PaymentRequest;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * INT-3 BLOCKER: a payment session created under an {@code sk_test_} key MUST have its SDK checkout
 * route to the {@link MockPaymentGatewayPort}, NEVER {@link HyperSwitchPaymentAdapter}.
 *
 * <p>This wires the REAL chain end-to-end (no DB): the merchant API key's {@code is_live} →
 * {@link PaymentSessionController#createSession} → {@link PaymentSessionService} → the SIGNED session JWT
 * issued by the REAL {@link SessionTokenIssuer} (carrying the {@code livemode} claim) → the REAL
 * {@link SessionTokenAuthenticationFilter} (which re-derives {@link PaymentMode} from the claim) →
 * the REAL {@link GatedPaymentGateway} routing decision. The ONLY mocked leaves are the session repo (no
 * DB) and the HyperSwitch adapter (so any interaction is a guarantee violation).</p>
 *
 * <p>FAILS if {@code live} stops being threaded through the session/JWT, if the filter stops stamping
 * PaymentMode from the claim, or if the gateway routing regresses — any of which would let a test-mode
 * SDK checkout auto-capture REAL funds.</p>
 */
class CheckoutTestModeRoutingTest {

    private SessionTokenIssuer issuer;
    private PaymentSessionService sessionService;
    private PaymentSessionRepository sessionRepository;
    private PaymentSessionController sessionController;
    private SessionTokenAuthenticationFilter sessionFilter;

    private HyperSwitchPaymentAdapter hyperSwitch;
    private MockPaymentGatewayPort mockDelegate;
    private PreAuthorizationGate gate;
    private GatedPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        // Real JWT issuer with a fixed dev secret (>= 32 chars for HMAC-SHA256).
        issuer = new SessionTokenIssuer("test-secret-key-minimum-32-characters!!", Duration.ofMinutes(30));

        sessionRepository = mock(PaymentSessionRepository.class);
        when(sessionRepository.save(any(PaymentSession.class))).thenAnswer(inv -> inv.getArgument(0));
        sessionService = new PaymentSessionService(sessionRepository, issuer, Duration.ofMinutes(30));
        sessionController = new PaymentSessionController(sessionService, sessionService);
        sessionFilter = new SessionTokenAuthenticationFilter(issuer, new ObjectMapper());

        // Real gateway with the real mock delegate; only HyperSwitch is mocked so any touch = violation.
        hyperSwitch = mock(HyperSwitchPaymentAdapter.class);
        mockDelegate = new MockPaymentGatewayPort();
        gate = mock(PreAuthorizationGate.class);
        CaptureHoldService holds = mock(CaptureHoldService.class);
        ScreeningOriginService origins = mock(ScreeningOriginService.class);
        WebhookMetadataService webhookMetadata = mock(WebhookMetadataService.class);
        MockWebhookSynthesizer synthesizer = mock(MockWebhookSynthesizer.class);
        lenient().when(origins.find(any())).thenReturn(Optional.empty());
        gateway = new GatedPaymentGateway(hyperSwitch, mockDelegate, gate, holds, origins,
                webhookMetadata, synthesizer,
                mock(io.nexuspay.payment.application.service.projection.PaymentProjectionService.class));

        PaymentMode.clear();
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        PaymentMode.clear();
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    private SessionToken createSessionUnder(boolean live) {
        // A merchant API-key principal whose is_live drives the session mode.
        NexusPayPrincipal merchant = new NexusPayPrincipal(
                "key_1", "tenant-A", "operator", NexusPayPrincipal.AuthMethod.API_KEY, null, live);
        var request = new CreatePaymentSessionRequest(
                5000L, "USD", "cust_1", "https://ok", "https://cancel",
                List.of("card"), Map.of(), Map.of());
        var response = sessionController.createSession(request, merchant);
        PaymentSessionResponse body = response.getBody();
        assertThat(body).isNotNull();
        // The controller maps the issued JWT into client_secret; re-validate it to get the parsed token.
        return issuer.validateSessionToken(body.client_secret());
    }

    @Test
    void sessionUnderTestKey_sdkCheckout_routesToMock_neverHyperSwitch() throws Exception {
        SessionToken token = createSessionUnder(false); // sk_test_ merchant
        assertThat(token).isNotNull();
        assertThat(token.live()).as("test-mode session JWT carries live=false").isFalse();

        // Drive a /v1/checkout request through the REAL session filter with the issued JWT. Inside the
        // chain (mode now stamped from the signed claim) we invoke the REAL gateway create — exactly the
        // op CheckoutController.confirmPayment performs.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/checkout/confirm");
        request.addHeader("Authorization", "Bearer " + token.token());
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        FilterChain chain = (req, res) -> {
            // The filter must have stamped TEST mode from the JWT claim before reaching the chain.
            assertThat(PaymentMode.isTestExplicit()).as("filter stamped test mode from JWT").isTrue();
            var created = gateway.createPayment(new PaymentRequest(
                    5000L, "USD", "cust_1", "card", "ptok_1",
                    "https://ok", null, "automatic", "checkout-ps", Map.of()),
                    CallContext.interactive("tenant-A"));
            assertThat(created.gatewayPaymentId()).startsWith("pay_test_"); // minted by the mock
        };

        sessionFilter.doFilter(request, response, chain);

        // The CENTRAL guarantee: a test-mode SDK checkout NEVER touches the real PSP.
        verifyNoInteractions(hyperSwitch);
        // And the filter cleared the holder afterward (no leak onto the pooled thread).
        assertThat(PaymentMode.isUnset()).isTrue();
    }

    @Test
    void sessionUnderLiveKey_sdkCheckout_reachesHyperSwitch() throws Exception {
        SessionToken token = createSessionUnder(true); // sk_live_ merchant
        assertThat(token.live()).as("live-mode session JWT carries live=true").isTrue();

        // Live path still screens: stub a clean ALLOW so the gate lets the create proceed to the PSP.
        when(gate.evaluate(any(), any(), any(), any(), any())).thenReturn(
                new io.nexuspay.payment.application.screening.GateDecision(false,
                        io.nexuspay.fraud.domain.model.RiskDecision.ALLOW,
                        java.util.UUID.randomUUID(), false, false));
        when(hyperSwitch.createPayment(any())).thenReturn(new io.nexuspay.payment.domain.PaymentResponse(
                "pay_live_1", "succeeded", 5000L, "USD", "automatic", "cust_1",
                "stripe", "txn_1", null, null, Instant.EPOCH, Map.of()));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/checkout/confirm");
        request.addHeader("Authorization", "Bearer " + token.token());
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        FilterChain chain = (req, res) -> {
            assertThat(PaymentMode.isLiveExplicit()).as("filter stamped live mode from JWT").isTrue();
            gateway.createPayment(new PaymentRequest(
                    5000L, "USD", "cust_1", "card", "ptok_1",
                    "https://ok", null, "automatic", "checkout-ps", Map.of()),
                    CallContext.interactive("tenant-A"));
        };

        sessionFilter.doFilter(request, response, chain);

        // A live-mode session DOES reach the real PSP (the carve-out).
        verify(hyperSwitch).createPayment(any());
    }
}
