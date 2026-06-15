package io.nexuspay.b2b.adapter.in.rest;

import io.nexuspay.b2b.application.port.in.ManageVendorPaymentUseCase;
import io.nexuspay.b2b.domain.VendorPaymentMethod;
import io.nexuspay.b2b.domain.VendorPaymentStatus;
import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.common.tenant.TenantPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest slice tests for {@link VendorPaymentController} (SEC-BATCH-1).
 *
 * @since SEC-BATCH-1
 */
@WebMvcTest(VendorPaymentController.class)
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

        mockMvc.perform(post("/v1/vendor-payments/vp_foreign/approve")
                .with(authentication(tenantAuth("tenant-1", "admin"))));

        verify(vendorPaymentUseCase).approveVendorPayment("vp_foreign", "tenant-1");
    }

    @Test
    void getPayment_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/v1/vendor-payments/vp_1"))
                .andExpect(status().isUnauthorized());
    }
}
