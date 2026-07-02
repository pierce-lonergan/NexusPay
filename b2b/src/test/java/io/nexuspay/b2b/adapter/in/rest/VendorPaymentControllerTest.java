package io.nexuspay.b2b.adapter.in.rest;

import io.nexuspay.b2b.application.port.in.ManageVendorPaymentUseCase;
import io.nexuspay.b2b.config.B2bProperties;
import io.nexuspay.b2b.domain.VendorPaymentMethod;
import io.nexuspay.b2b.domain.VendorPaymentStatus;
import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest slice tests for {@link VendorPaymentController} (SEC-BATCH-1, SEC-28).
 *
 * <p>NOTE: this slice does NOT load gateway-api's {@code GlobalExceptionHandler}, so an oversized batch
 * here is rejected by Spring's DEFAULT method-validation mapping (HandlerMethodValidationException -> 400
 * in Spring 6.1) rather than the production advice chain. The 400-through-the-real-advice contract (which
 * surfaced the 500-vs-400 GlobalExceptionHandler defect) is proven app-level in
 * {@code TenantIsolationIntegrationTest}. These slice tests regression-lock the cap itself: an oversized
 * or empty batch is rejected BEFORE the use case is ever invoked (no DB write).</p>
 *
 * @since SEC-BATCH-1
 */
@WebMvcTest(VendorPaymentController.class)
@Import(B2bProperties.class)   // SEC-28: the controller now injects B2bProperties for the configurable cap
class VendorPaymentControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private ManageVendorPaymentUseCase vendorPaymentUseCase;

    private static Authentication tenantAuth(String tenantId, String role) {
        TenantPrincipal principal = () -> tenantId;
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    /** GAP-068: approve endpoints require a full NexusPayPrincipal (userId = the maker identity). */
    private static Authentication principalAuth(String userId, String tenantId, String role) {
        var principal = new NexusPayPrincipal(userId, tenantId, role, NexusPayPrincipal.AuthMethod.JWT);
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static ManageVendorPaymentUseCase.VendorPaymentResult result(String id) {
        return new ManageVendorPaymentUseCase.VendorPaymentResult(
                id, "vendor-1", 100000, "USD", VendorPaymentMethod.ACH, VendorPaymentStatus.PAID,
                null, null, "ref_stub_1", null, Instant.now(), Instant.now());
    }

    @Test
    void getPayment_usesPrincipalTenant() throws Exception {
        when(vendorPaymentUseCase.getVendorPayment(eq("vp_1"), eq("tenant-1"))).thenReturn(result("vp_1"));

        mockMvc.perform(get("/v1/vendor-payments/vp_1")
                        .with(authentication(tenantAuth("tenant-1", "admin"))))
                .andExpect(status().isOk());

        verify(vendorPaymentUseCase).getVendorPayment("vp_1", "tenant-1");
    }

    @Test
    void approvePayment_belowThreshold_returns200_withPrincipalTenantAndUser() throws Exception {
        when(vendorPaymentUseCase.approveVendorPayment(eq("vp_1"), eq("tenant-1"), eq("user-maker")))
                .thenReturn(new ManageVendorPaymentUseCase.ApproveOutcome(result("vp_1"), null));

        mockMvc.perform(post("/v1/vendor-payments/vp_1/approve")
                        .with(authentication(principalAuth("user-maker", "tenant-1", "admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        verify(vendorPaymentUseCase).approveVendorPayment("vp_1", "tenant-1", "user-maker");
    }

    @Test
    void approvePayment_aboveThreshold_returns202_withApprovalIdAndThreshold() throws Exception {
        // GAP-068 (INT-2 refund-contract mirror): the pending outcome surfaces as 202 +
        // requires_approval=true + the approval id + the configured threshold (default 50000).
        when(vendorPaymentUseCase.approveVendorPayment(eq("vp_big"), eq("tenant-1"), eq("user-maker")))
                .thenReturn(new ManageVendorPaymentUseCase.ApproveOutcome(null, "appr_1"));

        mockMvc.perform(post("/v1/vendor-payments/vp_big/approve")
                        .with(authentication(principalAuth("user-maker", "tenant-1", "admin"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requires_approval").value(true))
                .andExpect(jsonPath("$.approval_id").value("appr_1"))
                .andExpect(jsonPath("$.status").value("pending_approval"))
                .andExpect(jsonPath("$.approval_threshold").value(50000));
    }

    @Test
    void approvePayment_withoutNexusPayPrincipal_returns403_failClosed() throws Exception {
        // GAP-068 FAIL-CLOSED: an auth that carries no NexusPayPrincipal (no attributable maker
        // identity) must be refused BEFORE the use case runs. ResponseStatusException(403) renders
        // even in this advice-less slice.
        mockMvc.perform(post("/v1/vendor-payments/vp_1/approve")
                        .with(authentication(tenantAuth("tenant-1", "admin"))))
                .andExpect(status().isForbidden());

        verify(vendorPaymentUseCase, never()).approveVendorPayment(any(), any(), any());
    }

    @Test
    void approvePayment_crossTenant_invokesServiceWithCallerTenant() throws Exception {
        // SEC-BATCH-1 headline: approving a foreign tenant's payment. Service throws not-found and is
        // invoked with the CALLER's tenant — money-moving approval cannot cross tenants.
        when(vendorPaymentUseCase.approveVendorPayment(eq("vp_foreign"), eq("tenant-1"), eq("user-maker")))
                .thenThrow(new ResourceNotFoundException("Vendor payment not found"));

        // The slice has no @ControllerAdvice (GlobalExceptionHandler lives in gateway-api), so the
        // not-found propagates out of perform — assert it raises, then verify the SECURITY property:
        // the service was invoked with the CALLER's tenant, not the path/header tenant. The →404
        // mapping is a global concern covered by the app-level TenantIsolationIntegrationTest.
        assertThatThrownBy(() -> mockMvc.perform(post("/v1/vendor-payments/vp_foreign/approve")
                .with(authentication(principalAuth("user-maker", "tenant-1", "admin")))));

        verify(vendorPaymentUseCase).approveVendorPayment("vp_foreign", "tenant-1", "user-maker");
    }

    @Test
    void getPayment_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/v1/vendor-payments/vp_1"))
                .andExpect(status().isUnauthorized());
    }

    // ---- SEC-28: batch DoS cap (the @Size/@NotEmpty control) ----------------------------------------

    /** A single valid request body element, used to build oversized/valid batches. */
    private static String validElementJson() {
        return "{\"vendorId\":\"vendor-1\",\"amount\":100000,\"currency\":\"USD\",\"method\":\"ACH\"}";
    }

    private static String batchJson(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> validElementJson())
                .collect(Collectors.joining(",", "[", "]"));
    }

    @Test
    void createBatch_overCap_rejectedBeforeAnyDbWrite() throws Exception {
        // SEC-28: a (MAX_BATCH_SIZE + 1)-element batch must be rejected BEFORE the use case is ever invoked
        // (no DB write). FAILS on the pre-SEC-28 unbounded controller (it would 201 and invoke the service).
        // Slice note: this b2b @WebMvcTest slice has no @ControllerAdvice — gateway-api's
        // GlobalExceptionHandler, which maps the @Size/@Validated violation to the 400 envelope, is off the
        // b2b test classpath — so the violation may propagate out of perform() rather than render a 400. The
        // REAL 400 status is asserted by the full-context app test
        // (TenantIsolationIntegrationTest#vendorPaymentBatch_overCap_returns400). The slice-level guarantee
        // is that the request is rejected and the use case is NEVER reached, regardless of how the
        // advice-less slice surfaces the rejection.
        try {
            mockMvc.perform(post("/v1/vendor-payments/batch")
                    .with(authentication(tenantAuth("tenant-1", "admin")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(batchJson(VendorPaymentController.MAX_BATCH_SIZE + 1)));
        } catch (Exception rejectedInAdviceLessSlice) {
            // The validation exception propagates in the advice-less slice — itself a rejection.
        }

        verify(vendorPaymentUseCase, never()).createBatch(any(), any());
    }

    @Test
    void createBatch_empty_rejectedBeforeAnyDbWrite() throws Exception {
        // SEC-28: @NotEmpty rejects a no-op empty batch before the use case runs. Slice surfaces the
        // rejection without the gateway-api advice (see createBatch_overCap_rejectedBeforeAnyDbWrite); the
        // real 400 is asserted by the full-context app test.
        try {
            mockMvc.perform(post("/v1/vendor-payments/batch")
                    .with(authentication(tenantAuth("tenant-1", "admin")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("[]"));
        } catch (Exception rejectedInAdviceLessSlice) {
            // propagated validation exception in the advice-less slice — a rejection
        }

        verify(vendorPaymentUseCase, never()).createBatch(any(), any());
    }

    @Test
    void createBatch_atCap_isAccepted() throws Exception {
        // SEC-28: a batch exactly AT the cap is valid and reaches the use case (the control rejects only
        // OVER the cap). Guards against an off-by-one that would reject legitimate max-size batches.
        when(vendorPaymentUseCase.createBatch(any(), eq("tenant-1")))
                .thenReturn(Collections.singletonList(result("vp_1")));

        mockMvc.perform(post("/v1/vendor-payments/batch")
                        .with(authentication(tenantAuth("tenant-1", "admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchJson(VendorPaymentController.MAX_BATCH_SIZE)))
                .andExpect(status().isCreated());

        verify(vendorPaymentUseCase).createBatch(any(), eq("tenant-1"));
    }
}
