package io.nexuspay.b2b.adapter.in.rest;

import io.nexuspay.b2b.application.port.in.ManageVendorPaymentUseCase;
import io.nexuspay.b2b.application.service.B2bApprovalService;
import io.nexuspay.b2b.domain.VendorPaymentMethod;
import io.nexuspay.b2b.domain.VendorPaymentStatus;
import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.nexuspay.iam.domain.PendingApproval;
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
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GAP-068 @WebMvcTest slice for {@link B2bApprovalController}: admin-only role gating, reviewer
 * identity threading (principal userId + tenant), fail-closed principal requirement, and the
 * response shapes.
 */
@WebMvcTest(B2bApprovalController.class)
class B2bApprovalControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private B2bApprovalService approvalService;

    private static Authentication principalAuth(String userId, String tenantId, String role) {
        var principal = new NexusPayPrincipal(userId, tenantId, role, NexusPayPrincipal.AuthMethod.JWT);
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static Authentication bareTenantAuth(String tenantId, String role) {
        TenantPrincipal principal = () -> tenantId;
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static PendingApproval approval(String status) {
        return new PendingApproval("appr_1", "vendor_payment_approve", "VendorPayment", "vp_1",
                Map.of("payment_id", "vp_1"), status, "user-maker", "user-reviewer",
                "tenant-1", Instant.now(), Instant.now());
    }

    private static B2bApprovalService.ReviewResult vendorReviewResult() {
        return new B2bApprovalService.ReviewResult(
                approval("APPROVED"),
                new ManageVendorPaymentUseCase.VendorPaymentResult(
                        "vp_1", "vendor-1", 100_000, "USD", VendorPaymentMethod.ACH,
                        VendorPaymentStatus.PAID, null, null, "ref_stub_1", null,
                        Instant.now(), Instant.now()),
                null);
    }

    @Test
    void approve_dispatchesWithPrincipalUserAndTenant_returnsExecutedResource() throws Exception {
        when(approvalService.reviewApprove("appr_1", "user-reviewer", "tenant-1"))
                .thenReturn(vendorReviewResult());

        mockMvc.perform(post("/v1/b2b/approvals/appr_1/approve")
                        .with(authentication(principalAuth("user-reviewer", "tenant-1", "admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value("vp_1"))
                .andExpect(jsonPath("$.status").value("PAID"));

        verify(approvalService).reviewApprove("appr_1", "user-reviewer", "tenant-1");
    }

    @Test
    void approve_operatorAndViewer_forbidden() throws Exception {
        mockMvc.perform(post("/v1/b2b/approvals/appr_1/approve")
                        .with(authentication(principalAuth("user-x", "tenant-1", "operator"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/b2b/approvals/appr_1/approve")
                        .with(authentication(principalAuth("user-x", "tenant-1", "viewer"))))
                .andExpect(status().isForbidden());

        verify(approvalService, never()).reviewApprove(any(), any(), any());
    }

    @Test
    void approve_withoutNexusPayPrincipal_returns403_failClosed() throws Exception {
        // GAP-068 FAIL-CLOSED: reviewing money movement requires an identifiable reviewer.
        mockMvc.perform(post("/v1/b2b/approvals/appr_1/approve")
                        .with(authentication(bareTenantAuth("tenant-1", "admin"))))
                .andExpect(status().isForbidden());

        verify(approvalService, never()).reviewApprove(any(), any(), any());
    }

    @Test
    void reject_returnsRejectedApprovalShape() throws Exception {
        when(approvalService.reviewReject("appr_1", "user-reviewer", "tenant-1"))
                .thenReturn(approval("REJECTED"));

        mockMvc.perform(post("/v1/b2b/approvals/appr_1/reject")
                        .with(authentication(principalAuth("user-reviewer", "tenant-1", "admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approval_id").value("appr_1"))
                .andExpect(jsonPath("$.status").value("rejected"))
                .andExpect(jsonPath("$.action").value("vendor_payment_approve"))
                .andExpect(jsonPath("$.resource_id").value("vp_1"));

        verify(approvalService).reviewReject("appr_1", "user-reviewer", "tenant-1");
    }

    @Test
    void approve_noAuth_returns401() throws Exception {
        mockMvc.perform(post("/v1/b2b/approvals/appr_1/approve"))
                .andExpect(status().isUnauthorized());
    }
}
