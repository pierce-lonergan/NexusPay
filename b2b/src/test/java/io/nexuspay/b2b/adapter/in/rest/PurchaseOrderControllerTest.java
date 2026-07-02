package io.nexuspay.b2b.adapter.in.rest;

import io.nexuspay.b2b.application.port.in.ManagePurchaseOrderUseCase;
import io.nexuspay.b2b.config.B2bProperties;
import io.nexuspay.b2b.domain.LineItem;
import io.nexuspay.b2b.domain.PaymentTerms;
import io.nexuspay.b2b.domain.PurchaseOrderStatus;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * @WebMvcTest tests for {@link PurchaseOrderController}.
 *
 * <p>SEC-23: the tenant is resolved from the authenticated {@link TenantPrincipal} via
 * {@code CallerTenant.require()}, never from an {@code X-Tenant-Id} header. These slice tests
 * therefore authenticate with a tenant-bearing principal (the {@code .with(authentication(...))}
 * post-processor) instead of {@code @WithMockUser} + a forged header.</p>
 *
 * @since 0.4.2 (Sprint 4.3)
 */
@WebMvcTest(PurchaseOrderController.class)
@Import(B2bProperties.class)   // GAP-068: the controller injects B2bProperties for the 202 threshold
class PurchaseOrderControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private ManagePurchaseOrderUseCase purchaseOrderUseCase;

    private static Authentication tenantAuth(String tenantId, String role) {
        TenantPrincipal principal = () -> tenantId;
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    /** GAP-068: approve requires a full NexusPayPrincipal (userId = the maker identity). */
    private static Authentication principalAuth(String userId, String tenantId, String role) {
        var principal = new NexusPayPrincipal(userId, tenantId, role, NexusPayPrincipal.AuthMethod.JWT);
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private final ManagePurchaseOrderUseCase.PurchaseOrderResult sampleResult =
            new ManagePurchaseOrderUseCase.PurchaseOrderResult(
                    "po_test123", "PO-001", "buyer-1", "seller-1",
                    100000, 10000, "USD", PurchaseOrderStatus.DRAFT,
                    PaymentTerms.NET_30, null,
                    List.of(new LineItem("Widget", 100, 1000, "WDGT", "EA")),
                    Instant.now());

    @Test
    void createPurchaseOrder_returns201() throws Exception {
        when(purchaseOrderUseCase.createPurchaseOrder(any())).thenReturn(sampleResult);

        mockMvc.perform(post("/v1/purchase-orders")
                        .with(authentication(tenantAuth("tenant-1", "admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"buyerId":"buyer-1","sellerId":"seller-1","poNumber":"PO-001",
                                 "currency":"USD","terms":"NET_30","taxAmount":10000,
                                 "lineItems":[{"description":"Widget","quantity":100,"unitCost":1000,"commodityCode":"WDGT","unitOfMeasure":"EA"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.poId").value("po_test123"))
                .andExpect(jsonPath("$.poNumber").value("PO-001"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.amount").value(100000));
    }

    @Test
    void getPurchaseOrder_returns200() throws Exception {
        when(purchaseOrderUseCase.getPurchaseOrder(eq("po_test123"), eq("tenant-1"))).thenReturn(sampleResult);

        mockMvc.perform(get("/v1/purchase-orders/po_test123")
                        .with(authentication(tenantAuth("tenant-1", "admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poId").value("po_test123"))
                .andExpect(jsonPath("$.buyerId").value("buyer-1"));

        // SEC-23: the use case is invoked with the CALLER's principal tenant.
        verify(purchaseOrderUseCase).getPurchaseOrder("po_test123", "tenant-1");
    }

    @Test
    void submitPurchaseOrder_returns200() throws Exception {
        var submittedResult = new ManagePurchaseOrderUseCase.PurchaseOrderResult(
                "po_test123", "PO-001", "buyer-1", "seller-1",
                100000, 10000, "USD", PurchaseOrderStatus.SUBMITTED,
                PaymentTerms.NET_30, null, List.of(), Instant.now());
        when(purchaseOrderUseCase.submitPurchaseOrder(eq("po_test123"), eq("tenant-1"))).thenReturn(submittedResult);

        mockMvc.perform(post("/v1/purchase-orders/po_test123/submit")
                        .with(authentication(tenantAuth("tenant-1", "admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    void cancelPurchaseOrder_returns204() throws Exception {
        mockMvc.perform(post("/v1/purchase-orders/po_test123/cancel")
                        .with(authentication(tenantAuth("tenant-1", "admin"))))
                .andExpect(status().isNoContent());

        // SEC-23: cancel is invoked with the CALLER's principal tenant, not a client-supplied header.
        verify(purchaseOrderUseCase).cancelPurchaseOrder("po_test123", "tenant-1");
    }

    @Test
    void approvePurchaseOrder_belowThreshold_returns200Approved() throws Exception {
        var approvedResult = new ManagePurchaseOrderUseCase.PurchaseOrderResult(
                "po_test123", "PO-001", "buyer-1", "seller-1",
                10000, 0, "USD", PurchaseOrderStatus.APPROVED,
                PaymentTerms.NET_30, null, List.of(), Instant.now());
        when(purchaseOrderUseCase.approvePurchaseOrder(eq("po_test123"), eq("tenant-1"), eq("user-maker")))
                .thenReturn(new ManagePurchaseOrderUseCase.ApproveOutcome(approvedResult, null));

        mockMvc.perform(post("/v1/purchase-orders/po_test123/approve")
                        .with(authentication(principalAuth("user-maker", "tenant-1", "admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        verify(purchaseOrderUseCase).approvePurchaseOrder("po_test123", "tenant-1", "user-maker");
    }

    @Test
    void approvePurchaseOrder_aboveThreshold_returns202Pending() throws Exception {
        // GAP-068 (INT-2 refund-contract mirror): 202 + requires_approval + approval id + threshold.
        when(purchaseOrderUseCase.approvePurchaseOrder(eq("po_big"), eq("tenant-1"), eq("user-maker")))
                .thenReturn(new ManagePurchaseOrderUseCase.ApproveOutcome(null, "appr_po_1"));

        mockMvc.perform(post("/v1/purchase-orders/po_big/approve")
                        .with(authentication(principalAuth("user-maker", "tenant-1", "admin"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requires_approval").value(true))
                .andExpect(jsonPath("$.approval_id").value("appr_po_1"))
                .andExpect(jsonPath("$.approval_threshold").value(50000));
    }

    @Test
    void approvePurchaseOrder_withoutNexusPayPrincipal_returns403_failClosed() throws Exception {
        // GAP-068 FAIL-CLOSED: no attributable maker identity → refused before the use case runs.
        mockMvc.perform(post("/v1/purchase-orders/po_test123/approve")
                        .with(authentication(tenantAuth("tenant-1", "admin"))))
                .andExpect(status().isForbidden());

        verify(purchaseOrderUseCase, never()).approvePurchaseOrder(any(), any(), any());
    }

    @Test
    void createPurchaseOrder_forbidden_forViewer() throws Exception {
        mockMvc.perform(post("/v1/purchase-orders")
                        .with(authentication(tenantAuth("tenant-1", "viewer")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"buyerId":"x","sellerId":"y","poNumber":"PO","currency":"USD"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void getPurchaseOrder_allowed_forViewer() throws Exception {
        when(purchaseOrderUseCase.getPurchaseOrder(eq("po_test123"), eq("tenant-1"))).thenReturn(sampleResult);

        mockMvc.perform(get("/v1/purchase-orders/po_test123")
                        .with(authentication(tenantAuth("tenant-1", "viewer"))))
                .andExpect(status().isOk());
    }
}
