package io.nexuspay.b2b.adapter.in.rest;

import io.nexuspay.b2b.application.port.in.ManagePurchaseOrderUseCase;
import io.nexuspay.b2b.domain.LineItem;
import io.nexuspay.b2b.domain.PaymentTerms;
import io.nexuspay.b2b.domain.PurchaseOrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * @WebMvcTest tests for {@link PurchaseOrderController}.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
@WebMvcTest(PurchaseOrderController.class)
class PurchaseOrderControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private ManagePurchaseOrderUseCase purchaseOrderUseCase;

    private final ManagePurchaseOrderUseCase.PurchaseOrderResult sampleResult =
            new ManagePurchaseOrderUseCase.PurchaseOrderResult(
                    "po_test123", "PO-001", "buyer-1", "seller-1",
                    100000, 10000, "USD", PurchaseOrderStatus.DRAFT,
                    PaymentTerms.NET_30, null,
                    List.of(new LineItem("Widget", 100, 1000, "WDGT", "EA")),
                    Instant.now());

    @Test
    @WithMockUser(roles = "admin")
    void createPurchaseOrder_returns201() throws Exception {
        when(purchaseOrderUseCase.createPurchaseOrder(any())).thenReturn(sampleResult);

        mockMvc.perform(post("/v1/purchase-orders")
                        .header("X-Tenant-Id", "tenant-1")
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
    @WithMockUser(roles = "admin")
    void getPurchaseOrder_returns200() throws Exception {
        when(purchaseOrderUseCase.getPurchaseOrder(eq("po_test123"), any())).thenReturn(sampleResult);

        mockMvc.perform(get("/v1/purchase-orders/po_test123")
                        .header("X-Tenant-Id", "tenant-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poId").value("po_test123"))
                .andExpect(jsonPath("$.buyerId").value("buyer-1"));
    }

    @Test
    @WithMockUser(roles = "admin")
    void submitPurchaseOrder_returns200() throws Exception {
        var submittedResult = new ManagePurchaseOrderUseCase.PurchaseOrderResult(
                "po_test123", "PO-001", "buyer-1", "seller-1",
                100000, 10000, "USD", PurchaseOrderStatus.SUBMITTED,
                PaymentTerms.NET_30, null, List.of(), Instant.now());
        when(purchaseOrderUseCase.submitPurchaseOrder(eq("po_test123"), any())).thenReturn(submittedResult);

        mockMvc.perform(post("/v1/purchase-orders/po_test123/submit")
                        .header("X-Tenant-Id", "tenant-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    @WithMockUser(roles = "admin")
    void cancelPurchaseOrder_returns204() throws Exception {
        mockMvc.perform(post("/v1/purchase-orders/po_test123/cancel")
                        .header("X-Tenant-Id", "tenant-1"))
                .andExpect(status().isNoContent());

        verify(purchaseOrderUseCase).cancelPurchaseOrder("po_test123", "tenant-1");
    }

    @Test
    @WithMockUser(roles = "viewer")
    void createPurchaseOrder_forbidden_forViewer() throws Exception {
        mockMvc.perform(post("/v1/purchase-orders")
                        .header("X-Tenant-Id", "tenant-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"buyerId":"x","sellerId":"y","poNumber":"PO","currency":"USD"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "viewer")
    void getPurchaseOrder_allowed_forViewer() throws Exception {
        when(purchaseOrderUseCase.getPurchaseOrder(eq("po_test123"), any())).thenReturn(sampleResult);

        mockMvc.perform(get("/v1/purchase-orders/po_test123")
                        .header("X-Tenant-Id", "tenant-1"))
                .andExpect(status().isOk());
    }
}
