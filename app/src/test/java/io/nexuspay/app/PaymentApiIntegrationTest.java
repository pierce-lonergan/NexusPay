package io.nexuspay.app;

import io.nexuspay.app.config.TestSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the Payment API endpoints.
 * Uses Testcontainers for PostgreSQL, Kafka, Valkey.
 * HyperSwitch calls are expected to fail (no HyperSwitch container) —
 * these tests validate the NexusPay layer (auth, validation, rate limiting).
 */
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class PaymentApiIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("POST /v1/payments - unauthenticated returns 401")
    void createPayment_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 5000, "currency": "USD"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /v1/payments - viewer role returns 403")
    void createPayment_viewerRole_returns403() throws Exception {
        mockMvc.perform(post("/v1/payments")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                TestSecurityConfig.authForRole("viewer")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 5000, "currency": "USD"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /v1/payments - invalid amount returns 400")
    void createPayment_invalidAmount_returns400() throws Exception {
        mockMvc.perform(post("/v1/payments")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                TestSecurityConfig.authForRole("admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 0, "currency": "USD"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /v1/payments - sanctioned destination country is blocked (403) before any PSP call")
    void createPayment_sanctionedDestination_returns403() throws Exception {
        // KP is in the default sanctioned list (nexuspay.fx.compliance.sanctioned-countries).
        // The B-003 pre-auth gate must reject BEFORE the PSP — a 403 (not a 5xx from a
        // failed HyperSwitch connection) proves the gate intercepted end-to-end.
        mockMvc.perform(post("/v1/payments")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                TestSecurityConfig.authForRole("admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 5000, "currency": "USD",
                                 "metadata": {"destination_country": "KP"}}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /actuator/health - public endpoint returns 200")
    void healthEndpoint_returns200() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /v1/ledger/accounts - authenticated returns 200")
    void ledgerAccounts_authenticated_returns200() throws Exception {
        mockMvc.perform(get("/v1/ledger/accounts")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                TestSecurityConfig.authForRole("admin"))))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("GET /v1/approvals - operator can list pending approvals")
    void listApprovals_operator_returns200() throws Exception {
        mockMvc.perform(get("/v1/approvals")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                TestSecurityConfig.authForRole("operator"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("POST /v1/approvals/{id}/approve - viewer returns 403")
    void approveRequest_viewer_returns403() throws Exception {
        mockMvc.perform(post("/v1/approvals/apr_fake/approve")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                TestSecurityConfig.authForRole("viewer"))))
                .andExpect(status().isForbidden());
    }
}
