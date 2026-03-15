package io.nexuspay.app;

import io.nexuspay.app.config.TestSecurityConfig;
import io.nexuspay.iam.application.ApprovalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the maker-checker approval workflow.
 */
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class MakerCheckerIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApprovalService approvalService;

    @Test
    @DisplayName("Approval workflow: create → list → approve")
    void approvalWorkflow_createListApprove() throws Exception {
        // Create a pending approval directly (simulates refund above threshold)
        var approval = approvalService.createApproval(
                "refund", "Payment", "pi_test_approval",
                Map.of("payment_id", "pi_test_approval", "amount", 75000, "currency", "USD", "reason", ""),
                "test-operator-user", "default"
        );

        // List pending approvals as operator
        mockMvc.perform(get("/v1/approvals")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                TestSecurityConfig.authForRole("operator"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + approval.getId() + "')]").exists())
                .andExpect(jsonPath("$[?(@.id=='" + approval.getId() + "')].status").value("pending_approval"));

        // Viewer cannot approve
        mockMvc.perform(post("/v1/approvals/" + approval.getId() + "/approve")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                TestSecurityConfig.authForRole("viewer"))))
                .andExpect(status().isForbidden());

        // Operator cannot approve (not admin)
        mockMvc.perform(post("/v1/approvals/" + approval.getId() + "/approve")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                TestSecurityConfig.authForRole("operator"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Self-approval is prevented")
    void selfApproval_isPrevented() throws Exception {
        // Create approval by operator-user
        var approval = approvalService.createApproval(
                "refund", "Payment", "pi_test_self_approval",
                Map.of("payment_id", "pi_test_self_approval", "amount", 60000, "currency", "USD", "reason", ""),
                "test-admin-user", "default"
        );

        // Same user (admin) tries to approve their own request
        mockMvc.perform(post("/v1/approvals/" + approval.getId() + "/approve")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                TestSecurityConfig.authForRole("admin"))))
                .andExpect(status().isForbidden());
    }
}
