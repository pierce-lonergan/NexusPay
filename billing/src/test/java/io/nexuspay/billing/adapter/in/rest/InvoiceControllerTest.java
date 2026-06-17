package io.nexuspay.billing.adapter.in.rest;

import io.nexuspay.billing.application.service.InvoiceGenerationService;
import io.nexuspay.billing.application.service.SubscriptionLifecycleService;
import io.nexuspay.billing.domain.Invoice;
import io.nexuspay.billing.domain.Subscription;
import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.common.mode.PaymentMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static io.nexuspay.billing.adapter.in.rest.TestAuth.authFor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-26 controller-slice tests for {@link InvoiceController}.
 *
 * <p>Asserts the effective tenant is the AUTHENTICATED principal's, never a client X-Tenant-Id header,
 * and that by-id reads/writes are tenant-scoped (foreign id → 404, no existence oracle). These FAIL on
 * the old header-trusting controller: {@code list} would call {@code listByTenant("victim-tenant")},
 * and {@code get} would call the unscoped {@code findById(id)} returning the victim's invoice (200).</p>
 */
@WebMvcTest(InvoiceController.class)
class InvoiceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InvoiceGenerationService invoiceService;

    // DX-5a: the controller now resolves the owning subscription's durable is_live for a manual pay.
    @MockBean
    private SubscriptionLifecycleService subscriptionService;

    // ---------- list: tenant from principal, header ignored ----------

    @Test
    void list_usesPrincipalTenant_andIgnoresSpoofedHeader() throws Exception {
        when(invoiceService.listByTenant(any(), eq(20), eq(0))).thenReturn(List.of());

        mockMvc.perform(get("/v1/invoices")
                        .header("X-Tenant-Id", "victim-tenant")   // spoofed — must be ignored
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isOk());

        verify(invoiceService).listByTenant(eq("tenant-a"), eq(20), eq(0));
        verify(invoiceService, never()).listByTenant(eq("victim-tenant"), eq(20), eq(0));
    }

    // ---------- by-id read: foreign id 404s via tenant-scoped finder ----------

    @Test
    void get_foreignTenantInvoice_returns404_andQueriesByPrincipalTenant() throws Exception {
        // Service is queried with the PRINCIPAL's tenant; a foreign invoice yields empty → 404.
        when(invoiceService.findById("inv_victim", "tenant-a")).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/invoices/{id}", "inv_victim")
                        .header("X-Tenant-Id", "victim-tenant")
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isNotFound());

        verify(invoiceService).findById("inv_victim", "tenant-a");
    }

    @Test
    void pay_foreignTenantInvoice_returns404_andQueriesByPrincipalTenant() throws Exception {
        when(invoiceService.findById("inv_victim", "tenant-a")).thenReturn(Optional.empty());

        mockMvc.perform(post("/v1/invoices/{id}/pay", "inv_victim")
                        .header("X-Tenant-Id", "victim-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payment_method_id\":\"pm_1\"}")
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isNotFound());

        verify(invoiceService).findById("inv_victim", "tenant-a");
        // The foreign invoice must never be collected against.
        verify(invoiceService, never()).collectPayment(any(), any(), anyBoolean());
    }

    @Test
    void lineItems_foreignTenantInvoice_returns404() throws Exception {
        // getLineItems asserts ownership first → ResourceNotFoundException (404) for a foreign id.
        when(invoiceService.getLineItems("inv_victim", "tenant-a"))
                .thenThrow(new ResourceNotFoundException("Invoice not found"));

        mockMvc.perform(get("/v1/invoices/{id}/line-items", "inv_victim")
                        .header("X-Tenant-Id", "victim-tenant")
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isNotFound());

        verify(invoiceService).getLineItems("inv_victim", "tenant-a");
    }

    // ---------- DX-5a money-safety: durable mode composes test-wins with the request mode ----------

    /**
     * BLOCKER (DX-5a): a TEST-mode request paying a LIVE subscription's invoice MUST route to the mock.
     * The request-thread TEST->mock fail-closed must NOT be overridden by the subscription's LIVE mode —
     * a {@code sk_test_} key can never reach the real PSP. resolveLiveMode composes test-wins
     * ({@code isLiveExplicit() && sub.isLive()}), so an UNSET/TEST request mode yields live=false here.
     */
    @Test
    void pay_testRequest_payingLiveSubscriptionInvoice_passesLiveFalse() throws Exception {
        Invoice invoice = mock(Invoice.class);
        when(invoice.getSubscriptionId()).thenReturn("sub_live");
        when(invoiceService.findById("inv_1", "tenant-a")).thenReturn(Optional.of(invoice));

        Subscription liveSub = mock(Subscription.class);
        when(liveSub.isLive()).thenReturn(true);
        when(subscriptionService.findById("sub_live", "tenant-a")).thenReturn(Optional.of(liveSub));

        when(invoiceService.collectPayment(eq(invoice), eq("pm_1"), anyBoolean())).thenReturn(true);

        // Simulate a TEST (is_live=false) request key on the controller's request thread. Cleared in
        // finally so the ThreadLocal never leaks onto the next test served by this pooled thread.
        PaymentMode.set(false);
        try {
            mockMvc.perform(post("/v1/invoices/{id}/pay", "inv_1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"payment_method_id\":\"pm_1\"}")
                            .with(authentication(authFor("tenant-a", "admin"))))
                    .andExpect(status().isOk());
        } finally {
            PaymentMode.clear();
        }

        // The charge must be routed as TEST (live=false) — never the real PSP — despite the LIVE sub.
        verify(invoiceService).collectPayment(eq(invoice), eq("pm_1"), eq(false));
        verify(invoiceService, never()).collectPayment(eq(invoice), eq("pm_1"), eq(true));
    }

    /**
     * DX-5a hardening (the original direction): a LIVE-mode request paying a TEST subscription's invoice
     * MUST also route to the mock (test-wins). Guards that the test-wins compose did not regress the
     * LIVE-key + TEST-sub case.
     */
    @Test
    void pay_liveRequest_payingTestSubscriptionInvoice_passesLiveFalse() throws Exception {
        Invoice invoice = mock(Invoice.class);
        when(invoice.getSubscriptionId()).thenReturn("sub_test");
        when(invoiceService.findById("inv_2", "tenant-a")).thenReturn(Optional.of(invoice));

        Subscription testSub = mock(Subscription.class);
        when(testSub.isLive()).thenReturn(false);
        when(subscriptionService.findById("sub_test", "tenant-a")).thenReturn(Optional.of(testSub));

        when(invoiceService.collectPayment(eq(invoice), eq("pm_1"), anyBoolean())).thenReturn(true);

        PaymentMode.set(true); // LIVE request key
        try {
            mockMvc.perform(post("/v1/invoices/{id}/pay", "inv_2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"payment_method_id\":\"pm_1\"}")
                            .with(authentication(authFor("tenant-a", "admin"))))
                    .andExpect(status().isOk());
        } finally {
            PaymentMode.clear();
        }

        verify(invoiceService).collectPayment(eq(invoice), eq("pm_1"), eq(false));
        verify(invoiceService, never()).collectPayment(eq(invoice), eq("pm_1"), eq(true));
    }

    // ---------- unauthenticated is rejected (no silent "default" tenant) ----------

    @Test
    void list_noPrincipal_isUnauthorized() throws Exception {
        mockMvc.perform(get("/v1/invoices")
                        .header("X-Tenant-Id", "default"))
                .andExpect(status().isUnauthorized());
    }
}
