package io.nexuspay.billing.adapter.in.rest;

import io.nexuspay.billing.application.service.InvoiceGenerationService;
import io.nexuspay.common.exception.ResourceNotFoundException;
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
import static org.mockito.ArgumentMatchers.eq;
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
        verify(invoiceService, never()).collectPayment(any(), any());
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

    // ---------- unauthenticated is rejected (no silent "default" tenant) ----------

    @Test
    void list_noPrincipal_isUnauthorized() throws Exception {
        mockMvc.perform(get("/v1/invoices")
                        .header("X-Tenant-Id", "default"))
                .andExpect(status().isUnauthorized());
    }
}
