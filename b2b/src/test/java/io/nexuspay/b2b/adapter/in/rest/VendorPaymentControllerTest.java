package io.nexuspay.b2b.adapter.in.rest;

import io.nexuspay.b2b.application.port.in.ManageVendorPaymentUseCase;
import io.nexuspay.b2b.config.B2bProperties;
import io.nexuspay.b2b.domain.VendorPaymentMethod;
import io.nexuspay.b2b.domain.VendorPaymentStatus;
import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.common.tenant.TenantPrincipal;
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

    private static ManageVendorPaymentUseCase.VendorPaymentResult result(String id) {
        return new ManageVendorPaymentUseCase.VendorPaymentResult(
                id, "vendor-1", 100000, "USD", VendorPaymentMethod.ACH, VendorPaymentStatus.APPROVED,
                null, null, null, null, null, Instant.now());
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
    void approvePayment_usesPrincipalTenant() throws Exception {
        when(vendorPaymentUseCase.approveVendorPayment(eq("vp_1"), eq("tenant-1"))).thenReturn(result("vp_1"));

        mockMvc.perform(post("/v1/vendor-payments/vp_1/approve")
                        .with(authentication(tenantAuth("tenant-1", "admin"))))
                .andExpect(status().isOk());

        verify(vendorPaymentUseCase).approveVendorPayment("vp_1", "tenant-1");
    }

    @Test
    void approvePayment_crossTenant_invokesServiceWithCallerTenant() throws Exception {
        // SEC-BATCH-1 headline: approving a foreign tenant's payment. Service throws not-found and is
        // invoked with the CALLER's tenant — money-moving approval cannot cross tenants.
        when(vendorPaymentUseCase.approveVendorPayment(eq("vp_foreign"), eq("tenant-1")))
                .thenThrow(new ResourceNotFoundException("Vendor payment not found"));

        // The slice has no @ControllerAdvice (GlobalExceptionHandler lives in gateway-api), so the
        // not-found propagates out of perform — assert it raises, then verify the SECURITY property:
        // the service was invoked with the CALLER's tenant, not the path/header tenant. The →404
        // mapping is a global concern covered by the app-level TenantIsolationIntegrationTest.
        assertThatThrownBy(() -> mockMvc.perform(post("/v1/vendor-payments/vp_foreign/approve")
                .with(authentication(tenantAuth("tenant-1", "admin")))));

        verify(vendorPaymentUseCase).approveVendorPayment("vp_foreign", "tenant-1");
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
    void createBatch_overCap_returns400_beforeAnyDbWrite() throws Exception {
        // SEC-28: a (MAX_BATCH_SIZE + 1)-element batch must be rejected 400 BEFORE the use case is ever
        // invoked (no DB write). FAILS on the pre-SEC-28 unbounded controller (it would 201 / invoke the
        // service). The slice maps HandlerMethodValidationException -> 400 (Spring default).
        mockMvc.perform(post("/v1/vendor-payments/batch")
                        .with(authentication(tenantAuth("tenant-1", "admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchJson(VendorPaymentController.MAX_BATCH_SIZE + 1)))
                .andExpect(status().isBadRequest());

        verify(vendorPaymentUseCase, never()).createBatch(any(), any());
    }

    @Test
    void createBatch_empty_returns400_beforeAnyDbWrite() throws Exception {
        // SEC-28: @NotEmpty rejects a no-op empty batch -> 400, no service invocation.
        mockMvc.perform(post("/v1/vendor-payments/batch")
                        .with(authentication(tenantAuth("tenant-1", "admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isBadRequest());

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
