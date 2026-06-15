package io.nexuspay.marketplace.adapter.in.rest;

import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.marketplace.application.port.in.ConfigureFeeUseCase;
import io.nexuspay.marketplace.application.port.in.OnboardAccountUseCase;
import io.nexuspay.marketplace.domain.AccountState;
import io.nexuspay.marketplace.domain.KycStatus;
import io.nexuspay.marketplace.domain.PayoutSchedule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * @WebMvcTest tests for {@link ConnectedAccountController}.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
@WebMvcTest(ConnectedAccountController.class)
class ConnectedAccountControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private OnboardAccountUseCase onboardUseCase;
    @MockBean private ConfigureFeeUseCase feeUseCase;

    /** Tenant-bearing auth built from the common {@link TenantPrincipal} (no iam import needed). */
    private static Authentication tenantAuth(String tenantId, String role) {
        TenantPrincipal principal = () -> tenantId;
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void onboardAccount_returns201() throws Exception {
        when(onboardUseCase.onboardAccount(any())).thenReturn(
                new OnboardAccountUseCase.OnboardResult("ca_test123", "Acme Corp", AccountState.ONBOARDING, KycStatus.IN_REVIEW));
        when(onboardUseCase.getAccount(eq("ca_test123"), eq("tenant-1"))).thenReturn(
                new OnboardAccountUseCase.AccountInfo("ca_test123", "tenant-1", "Acme Corp", "acme@test.com",
                        AccountState.ONBOARDING, KycStatus.IN_REVIEW, "US", "USD",
                        PayoutSchedule.DAILY, 0, BigDecimal.ZERO, 0, Instant.now(), Instant.now()));

        mockMvc.perform(post("/v1/connected-accounts")
                        .with(authentication(tenantAuth("tenant-1", "admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessName":"Acme Corp","email":"acme@test.com","country":"US","defaultCurrency":"USD"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId").value("ca_test123"))
                .andExpect(jsonPath("$.businessName").value("Acme Corp"))
                .andExpect(jsonPath("$.status").value("ONBOARDING"));
    }

    @Test
    void getAccount_returns200() throws Exception {
        when(onboardUseCase.getAccount(eq("ca_test123"), eq("tenant-1"))).thenReturn(
                new OnboardAccountUseCase.AccountInfo("ca_test123", "tenant-1", "Acme Corp", "acme@test.com",
                        AccountState.ACTIVE, KycStatus.VERIFIED, "US", "USD",
                        PayoutSchedule.WEEKLY, 1000, new BigDecimal("2.50"), 30, Instant.now(), Instant.now()));

        mockMvc.perform(get("/v1/connected-accounts/ca_test123")
                        .with(authentication(tenantAuth("tenant-1", "admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("ca_test123"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.kycStatus").value("VERIFIED"));
    }

    @Test
    void suspendAccount_returns204() throws Exception {
        mockMvc.perform(post("/v1/connected-accounts/ca_test123/suspend")
                        .with(authentication(tenantAuth("tenant-1", "admin")))
                        .param("reason", "Fraud detected"))
                .andExpect(status().isNoContent());

        // Tenant comes from the principal, not a header.
        verify(onboardUseCase).suspendAccount("ca_test123", "tenant-1", "Fraud detected");
    }

    @Test
    void deleteAccount_returns204() throws Exception {
        mockMvc.perform(delete("/v1/connected-accounts/ca_test123")
                        .with(authentication(tenantAuth("tenant-1", "admin"))))
                .andExpect(status().isNoContent());

        verify(onboardUseCase).closeAccount("ca_test123", "tenant-1");
    }

    @Test
    void suspendAccount_ignoresClientHeader_usesPrincipalTenant() throws Exception {
        // SEC-05/06: a spoofed X-Tenant-Id must not change the tenant the suspend is scoped to.
        mockMvc.perform(post("/v1/connected-accounts/ca_test123/suspend")
                        .header("X-Tenant-Id", "tenant-evil")
                        .with(authentication(tenantAuth("tenant-1", "admin")))
                        .param("reason", "Fraud detected"))
                .andExpect(status().isNoContent());

        verify(onboardUseCase).suspendAccount("ca_test123", "tenant-1", "Fraud detected");
    }

    @Test
    void onboardAccount_forbidden_forViewer() throws Exception {
        mockMvc.perform(post("/v1/connected-accounts")
                        .with(authentication(tenantAuth("tenant-1", "viewer")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessName":"X","email":"x@t.com","country":"US","defaultCurrency":"USD"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void configureFee_returns200() throws Exception {
        when(feeUseCase.configureFee(any())).thenReturn(
                new ConfigureFeeUseCase.FeeConfigResult("ca_test123", new BigDecimal("5.00"), 50));

        mockMvc.perform(post("/v1/connected-accounts/ca_test123/fees")
                        .with(authentication(tenantAuth("tenant-1", "admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"connectedAccountId":"ca_test123","feePercent":5.00,"feeFixed":50}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feePercent").value(5.00))
                .andExpect(jsonPath("$.feeFixed").value(50));
    }
}
