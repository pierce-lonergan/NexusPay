package io.nexuspay.marketplace.adapter.in.rest;

import io.nexuspay.marketplace.application.port.in.ConfigureFeeUseCase;
import io.nexuspay.marketplace.application.port.in.OnboardAccountUseCase;
import io.nexuspay.marketplace.domain.AccountState;
import io.nexuspay.marketplace.domain.KycStatus;
import io.nexuspay.marketplace.domain.PayoutSchedule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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

    @Test
    @WithMockUser(roles = "admin")
    void onboardAccount_returns201() throws Exception {
        when(onboardUseCase.onboardAccount(any())).thenReturn(
                new OnboardAccountUseCase.OnboardResult("ca_test123", "Acme Corp", AccountState.ONBOARDING, KycStatus.IN_REVIEW));
        when(onboardUseCase.getAccount(eq("ca_test123"), any())).thenReturn(
                new OnboardAccountUseCase.AccountInfo("ca_test123", "tenant-1", "Acme Corp", "acme@test.com",
                        AccountState.ONBOARDING, KycStatus.IN_REVIEW, "US", "USD",
                        PayoutSchedule.DAILY, 0, BigDecimal.ZERO, 0, Instant.now(), Instant.now()));

        mockMvc.perform(post("/v1/connected-accounts")
                        .header("X-Tenant-Id", "tenant-1")
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
    @WithMockUser(roles = "admin")
    void getAccount_returns200() throws Exception {
        when(onboardUseCase.getAccount(eq("ca_test123"), any())).thenReturn(
                new OnboardAccountUseCase.AccountInfo("ca_test123", "tenant-1", "Acme Corp", "acme@test.com",
                        AccountState.ACTIVE, KycStatus.VERIFIED, "US", "USD",
                        PayoutSchedule.WEEKLY, 1000, new BigDecimal("2.50"), 30, Instant.now(), Instant.now()));

        mockMvc.perform(get("/v1/connected-accounts/ca_test123")
                        .header("X-Tenant-Id", "tenant-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("ca_test123"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.kycStatus").value("VERIFIED"));
    }

    @Test
    @WithMockUser(roles = "admin")
    void suspendAccount_returns204() throws Exception {
        mockMvc.perform(post("/v1/connected-accounts/ca_test123/suspend")
                        .header("X-Tenant-Id", "tenant-1")
                        .param("reason", "Fraud detected"))
                .andExpect(status().isNoContent());

        verify(onboardUseCase).suspendAccount("ca_test123", "tenant-1", "Fraud detected");
    }

    @Test
    @WithMockUser(roles = "admin")
    void deleteAccount_returns204() throws Exception {
        mockMvc.perform(delete("/v1/connected-accounts/ca_test123")
                        .header("X-Tenant-Id", "tenant-1"))
                .andExpect(status().isNoContent());

        verify(onboardUseCase).closeAccount("ca_test123", "tenant-1");
    }

    @Test
    @WithMockUser(roles = "viewer")
    void onboardAccount_forbidden_forViewer() throws Exception {
        mockMvc.perform(post("/v1/connected-accounts")
                        .header("X-Tenant-Id", "tenant-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessName":"X","email":"x@t.com","country":"US","defaultCurrency":"USD"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "admin")
    void configureFee_returns200() throws Exception {
        when(feeUseCase.configureFee(any())).thenReturn(
                new ConfigureFeeUseCase.FeeConfigResult("ca_test123", new BigDecimal("5.00"), 50));

        mockMvc.perform(post("/v1/connected-accounts/ca_test123/fees")
                        .header("X-Tenant-Id", "tenant-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"connectedAccountId":"ca_test123","feePercent":5.00,"feeFixed":50}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feePercent").value(5.00))
                .andExpect(jsonPath("$.feeFixed").value(50));
    }
}
