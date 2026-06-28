package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.gateway.application.RefundOrchestrationService;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.application.screening.ScreeningOriginService;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.RefundResponse;
import io.nexuspay.payment.domain.PaymentRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DX-5c-ii: end-to-end {@code @PreAuthorize} scope enforcement on the gateway-api money surface, driven
 * through the REAL method-security pipeline + the real {@code @scopeAuth} bean (registered by
 * {@code GatewayTestApplication}). This is the gateway-api counterpart of
 * {@code PayoutControllerScopeEnforcementTest} and proves the guards on {@code PaymentController} actually
 * FIRE a 403 (not merely that the scope strings are spelled right): the existing direct-construction
 * controller tests never evaluate {@code @PreAuthorize}.
 *
 * <p>PaymentController guards under test:</p>
 * <ul>
 *   <li>{@code GET /v1/payments/{id}} → {@code payments:read}</li>
 *   <li>{@code POST /v1/payments} → {@code payments:write}</li>
 *   <li>{@code POST /v1/payments/{id}/refunds} → {@code refunds:write} (the refund-money path)</li>
 * </ul>
 *
 * <p>Asserts, fail-closed: a key scoped to {@code payments:read} is FORBIDDEN (403) on a write endpoint
 * and on the refund path; the refund path additionally rejects a {@code payments:write}-only key (proving
 * refund creation is governed by {@code refunds:write}, not {@code payments:write}); an UNRESTRICTED
 * (no-scopes) key is allowed on ALL (back-compat); and the scope check is AND-composed with the role
 * (right scope + wrong role → still 403).</p>
 */
// Exclude the gateway-api servlet filters (io.nexuspay.gateway.adapter.in.filter.*): they are @Components
// that @WebMvcTest would instantiate, and three (IdempotencyFilter / RateLimitFilter /
// InternalWebhookRateLimitFilter) require a StringRedisTemplate bean absent from a web slice -> the context
// fails to load with NoSuchBeanDefinitionException. This test exercises @PreAuthorize METHOD security on
// PaymentController, which is independent of those servlet filters; method-security denials still surface as
// 403 via GlobalExceptionHandler's @ExceptionHandler(AccessDeniedException). (gateway-api's first @WebMvcTest.)
@WebMvcTest(controllers = PaymentController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "io\\.nexuspay\\.gateway\\.adapter\\.in\\.filter\\..*"))
class PaymentControllerScopeEnforcementTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private PaymentGatewayPort paymentGateway;
    @MockBean private RefundOrchestrationService refundOrchestration;
    @MockBean private ScreeningOriginService screeningOrigins;
    // TEST-3c: PaymentController now also depends on OffSessionChargeService — supply a mock so the
    // @WebMvcTest context can construct the controller (the scope tests never send a payment_method).
    @MockBean private io.nexuspay.payment.application.service.OffSessionChargeService offSessionCharge;
    // GAP-076: PaymentController now also depends on the read-model query service (for GET /v1/payments) —
    // supply a mock so the @WebMvcTest context can construct the controller (the scope tests never list).
    @MockBean private io.nexuspay.payment.application.service.projection.PaymentProjectionQueryService projectionQuery;

    private static Authentication auth(String role, Set<String> scopes) {
        return auth(role, scopes, true);
    }

    private static Authentication auth(String role, Set<String> scopes, boolean live) {
        // NexusPayPrincipal is the production principal; scopes null/empty == UNRESTRICTED (back-compat),
        // non-empty == restricted to exactly those scopes. live==false models an sk_test_ key.
        var principal = new NexusPayPrincipal(
                "user-1", "tenant-1", role, NexusPayPrincipal.AuthMethod.API_KEY, null, live, scopes);
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static final String CREATE_BODY = """
            { "amount": 5000, "currency": "USD" }
            """;

    private static final String REFUND_BODY = """
            { "amount": 2500, "currency": "USD", "reason": "duplicate" }
            """;

    private PaymentResponse okPayment() {
        return new PaymentResponse("pay_1", PaymentResponse.STATUS_REQUIRES_CAPTURE, 5000, "USD",
                "manual", "cus_1", "stripe", "con_1", null, null, Instant.now(), Map.of());
    }

    private void stubGetPayment() {
        when(paymentGateway.getPayment(anyString())).thenReturn(okPayment());
    }

    private void stubCreatePayment() {
        when(paymentGateway.createPayment(any(), any())).thenReturn(okPayment());
    }

    private void stubSubThresholdRefund() {
        var refund = new RefundResponse("rfnd_1", "pay_1", RefundResponse.STATUS_SUCCEEDED, 2500L, "USD",
                "duplicate", "stripe", "con_1", null, null, Instant.now());
        when(refundOrchestration.createRefund(anyString(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(new RefundOrchestrationService.RefundResult(refund, null));
    }

    // --- read-scoped key: allowed on read, forbidden on writes ---

    @Test
    void readScopedKey_allowedOnReadEndpoint() throws Exception {
        stubGetPayment();
        mockMvc.perform(get("/v1/payments/pay_1")
                        .with(authentication(auth("operator", Set.of("payments:read")))))
                .andExpect(status().isOk());
    }

    @Test
    void readScopedKey_forbiddenOnCreate() throws Exception {
        mockMvc.perform(post("/v1/payments")
                        .with(authentication(auth("operator", Set.of("payments:read"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void readScopedKey_forbiddenOnRefund() throws Exception {
        mockMvc.perform(post("/v1/payments/pay_1/refunds")
                        .with(authentication(auth("operator", Set.of("payments:read"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REFUND_BODY))
                .andExpect(status().isForbidden());
    }

    // --- refund is governed by refunds:write, NOT payments:write ---

    @Test
    void paymentsWriteOnlyKey_forbiddenOnRefund() throws Exception {
        // A key scoped to payments:write (but NOT refunds:write) can create/capture payments but must NOT
        // be able to issue a refund — refund creation requires refunds:write.
        mockMvc.perform(post("/v1/payments/pay_1/refunds")
                        .with(authentication(auth("operator", Set.of("payments:write"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REFUND_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void refundsWriteScopedKey_allowedOnRefund() throws Exception {
        stubSubThresholdRefund();
        mockMvc.perform(post("/v1/payments/pay_1/refunds")
                        .with(authentication(auth("operator", Set.of("refunds:write"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REFUND_BODY))
                .andExpect(status().isCreated());
    }

    // --- unrestricted (no-scopes) key: allowed on ALL (back-compat) ---

    @Test
    void unrestrictedKey_allowedOnReadCreateAndRefund() throws Exception {
        stubGetPayment();
        mockMvc.perform(get("/v1/payments/pay_1")
                        .with(authentication(auth("operator", null))))
                .andExpect(status().isOk());

        stubCreatePayment();
        mockMvc.perform(post("/v1/payments")
                        .with(authentication(auth("operator", null)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated());

        stubSubThresholdRefund();
        mockMvc.perform(post("/v1/payments/pay_1/refunds")
                        .with(authentication(auth("operator", null)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REFUND_BODY))
                .andExpect(status().isCreated());
    }

    // --- AND-composition with role: right scope, wrong role -> still denied ---

    @Test
    void writeScopedKey_butWrongRole_stillForbidden() throws Exception {
        // viewer has the write SCOPE but NOT the write ROLE (POST requires admin/operator). The AND
        // composition denies: scopes NARROW the role, they never grant a role the key lacks.
        mockMvc.perform(post("/v1/payments")
                        .with(authentication(auth("viewer", Set.of("payments:write"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isForbidden());
    }

    // --- TEST-3c: the off-session snake_case JSON contract is bound by Jackson through the REAL HTTP path ---
    // (closes the TEST-3b snake_case-binding trap: the only other off-session controller tests build
    // CreatePaymentRequest via its Java constructor, so a refactor to camelCase Java names + @JsonProperty,
    // or a tri-state Boolean off_session binding subtlety, would break every real client while passing them.)

    private PaymentResponse okSucceededPayment() {
        return new PaymentResponse("pay_off_1", PaymentResponse.STATUS_SUCCEEDED, 5000L, "USD",
                "automatic", "cus_1", "mock", "txn_1", null, null, Instant.now(), Map.of());
    }

    /**
     * A real client POSTing a JSON body with the snake_case off-session keys
     * ({@code payment_method}/{@code off_session}/{@code setup_future_usage}/{@code mandate_id}) must (a)
     * have Jackson bind those keys onto {@link io.nexuspay.gateway.adapter.in.rest.dto.CreatePaymentRequest}
     * and (b) trigger delegation to {@link io.nexuspay.payment.application.service.OffSessionChargeService}
     * with EXACTLY those values — proving the public off-session HTTP entrypoint really fires. Uses an
     * sk_test_ principal ({@code live==false}) so the server-derived {@code isTest} flows through as TRUE,
     * never read from the body.
     */
    @Test
    void offSessionSnakeCaseBody_boundByJackson_delegatesWithExactValues() throws Exception {
        when(offSessionCharge.charge(anyString(), anyString(), anyLong(), anyString(),
                any(), any(), any(), anyBoolean(), any(), any())).thenReturn(okSucceededPayment());

        String offSessionBody = """
                {
                  "amount": 5000,
                  "currency": "USD",
                  "payment_method": "pm_123",
                  "off_session": true,
                  "setup_future_usage": "off_session",
                  "mandate_id": "m_1"
                }
                """;

        mockMvc.perform(post("/v1/payments")
                        .header("Idempotency-Key", "idem-off-1")
                        .with(authentication(auth("operator", Set.of("payments:write"), false)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(offSessionBody))
                .andExpect(status().isCreated());

        // Jackson actually bound the snake_case keys AND the controller derived isTest=TRUE from the
        // sk_test_ principal (live==false), tenant from the principal — never from the body.
        verify(offSessionCharge).charge(
                eq("tenant-1"), eq("pm_123"), eq(5000L), eq("USD"),
                eq(Boolean.TRUE), eq("off_session"), eq("m_1"),
                eq(true), eq("idem-off-1"), any());
        // The off-session path replaces the inline path — the gateway is NOT called directly here.
        verifyNoInteractions(paymentGateway);
    }

    /**
     * Back-compat through the SAME JSON binder: a create WITHOUT {@code payment_method} takes the inline
     * path — the off-session service is never invoked — and the inline {@link PaymentRequest} carries all
     * four off-session fields as null (byte-identical to pre-3c).
     */
    @Test
    void createWithoutPaymentMethod_takesInlinePath_offSessionFieldsNull() throws Exception {
        when(paymentGateway.createPayment(any(PaymentRequest.class), any())).thenReturn(okSucceededPayment());

        mockMvc.perform(post("/v1/payments")
                        .with(authentication(auth("operator", Set.of("payments:write"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated());

        verify(offSessionCharge, never()).charge(anyString(), anyString(), anyLong(), anyString(),
                any(), any(), any(), anyBoolean(), any(), any());
        ArgumentCaptor<PaymentRequest> cap = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(paymentGateway).createPayment(cap.capture(), any());
        PaymentRequest req = cap.getValue();
        org.assertj.core.api.Assertions.assertThat(req.paymentMethod()).isNull();
        org.assertj.core.api.Assertions.assertThat(req.offSession()).isNull();
        org.assertj.core.api.Assertions.assertThat(req.setupFutureUsage()).isNull();
        org.assertj.core.api.Assertions.assertThat(req.mandateId()).isNull();
    }
}
