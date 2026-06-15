package io.nexuspay.marketplace.adapter.in.rest;

import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.marketplace.application.port.in.SchedulePayoutUseCase;
import io.nexuspay.marketplace.domain.PayoutMethod;
import io.nexuspay.marketplace.domain.PayoutStatus;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest slice tests for {@link PayoutController}. SEC-BATCH-1: tenant is resolved from the
 * authenticated principal (no X-Tenant-Id header). Cross-tenant requests surface as a
 * {@link ResourceNotFoundException} at the service boundary; the HTTP 404 mapping is asserted in the
 * app-module integration test where gateway-api's GlobalExceptionHandler is on the advice chain.
 *
 * @since SEC-BATCH-1
 */
@WebMvcTest(PayoutController.class)
class PayoutControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private SchedulePayoutUseCase payoutUseCase;

    private static Authentication tenantAuth(String tenantId, String role) {
        TenantPrincipal principal = () -> tenantId;
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void getPayout_usesPrincipalTenant() throws Exception {
        when(payoutUseCase.getPayout(eq("po_1"), eq("tenant-1"))).thenReturn(
                new SchedulePayoutUseCase.PayoutResult("po_1", "ca_1", 5000, "USD",
                        PayoutStatus.PENDING, PayoutMethod.BANK_TRANSFER, null, null, null, null, Instant.now()));

        mockMvc.perform(get("/v1/payouts/po_1")
                        .with(authentication(tenantAuth("tenant-1", "admin"))))
                .andExpect(status().isOk());

        // Authority comes from the principal — even a spoofed header would not change this.
        verify(payoutUseCase).getPayout("po_1", "tenant-1");
    }

    @Test
    void getPayout_ignoresClientHeader() throws Exception {
        when(payoutUseCase.getPayout(eq("po_1"), eq("tenant-1"))).thenReturn(
                new SchedulePayoutUseCase.PayoutResult("po_1", "ca_1", 5000, "USD",
                        PayoutStatus.PENDING, PayoutMethod.BANK_TRANSFER, null, null, null, null, Instant.now()));

        mockMvc.perform(get("/v1/payouts/po_1")
                        .header("X-Tenant-Id", "tenant-evil")
                        .with(authentication(tenantAuth("tenant-1", "admin"))))
                .andExpect(status().isOk());

        verify(payoutUseCase).getPayout("po_1", "tenant-1");
    }

    @Test
    void getPayout_crossTenant_invokesServiceWithCallerTenant() throws Exception {
        // SEC-BATCH-1: caller tenant-1 requests a payout owned by tenant-2. The use case throws
        // ResourceNotFoundException (→ 404 once the advice chain is present). The load-bearing
        // assertion here is that the service was invoked with the CALLER's tenant, never the resource's.
        when(payoutUseCase.getPayout(eq("po_foreign"), eq("tenant-1")))
                .thenThrow(new ResourceNotFoundException("Payout not found"));

        mockMvc.perform(get("/v1/payouts/po_foreign")
                .with(authentication(tenantAuth("tenant-1", "admin"))));

        verify(payoutUseCase).getPayout("po_foreign", "tenant-1");
    }

    @Test
    void getPayout_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/v1/payouts/po_1"))
                .andExpect(status().isUnauthorized());
    }
}
