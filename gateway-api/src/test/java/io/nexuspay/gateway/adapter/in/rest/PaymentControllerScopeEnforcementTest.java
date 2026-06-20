package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.gateway.application.RefundOrchestrationService;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.application.screening.ScreeningOriginService;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.RefundResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
@WebMvcTest(PaymentController.class)
class PaymentControllerScopeEnforcementTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private PaymentGatewayPort paymentGateway;
    @MockBean private RefundOrchestrationService refundOrchestration;
    @MockBean private ScreeningOriginService screeningOrigins;

    private static Authentication auth(String role, Set<String> scopes) {
        // NexusPayPrincipal is the production principal; scopes null/empty == UNRESTRICTED (back-compat),
        // non-empty == restricted to exactly those scopes.
        var principal = new NexusPayPrincipal(
                "user-1", "tenant-1", role, NexusPayPrincipal.AuthMethod.API_KEY, null, true, scopes);
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
}
