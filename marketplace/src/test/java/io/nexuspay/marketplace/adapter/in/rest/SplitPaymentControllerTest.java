package io.nexuspay.marketplace.adapter.in.rest;

import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.marketplace.application.port.in.CreateSplitPaymentUseCase;
import io.nexuspay.marketplace.domain.SplitPaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest slice tests for {@link SplitPaymentController} (SEC-BATCH-1).
 *
 * @since SEC-BATCH-1
 */
@WebMvcTest(SplitPaymentController.class)
class SplitPaymentControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private CreateSplitPaymentUseCase splitPaymentUseCase;

    private static Authentication tenantAuth(String tenantId, String role) {
        TenantPrincipal principal = () -> tenantId;
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void getSplitPayment_usesPrincipalTenant() throws Exception {
        when(splitPaymentUseCase.getSplitPayment(eq("sp_1"), eq("tenant-1"))).thenReturn(
                new CreateSplitPaymentUseCase.SplitPaymentResult("sp_1", "pi_1",
                        SplitPaymentStatus.PROCESSING, 10000, "USD", List.of(), 0));

        mockMvc.perform(get("/v1/split-payments/sp_1")
                        .with(authentication(tenantAuth("tenant-1", "admin"))))
                .andExpect(status().isOk());

        verify(splitPaymentUseCase).getSplitPayment("sp_1", "tenant-1");
    }

    @Test
    void getSplitPayment_crossTenant_invokesServiceWithCallerTenant() throws Exception {
        when(splitPaymentUseCase.getSplitPayment(eq("sp_foreign"), eq("tenant-1")))
                .thenThrow(new ResourceNotFoundException("Split payment not found"));

        mockMvc.perform(get("/v1/split-payments/sp_foreign")
                .with(authentication(tenantAuth("tenant-1", "admin"))));

        verify(splitPaymentUseCase).getSplitPayment("sp_foreign", "tenant-1");
    }

    @Test
    void getSplitPayment_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/v1/split-payments/sp_1"))
                .andExpect(status().isUnauthorized());
    }
}
