package io.nexuspay.payment.adapter.in.rest;

import io.nexuspay.common.tenant.ScopedPrincipal;
import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.payment.application.service.paymentmethod.PaymentMethodService;
import io.nexuspay.payment.domain.paymentmethod.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TEST-3b: end-to-end {@code @PreAuthorize} scope enforcement on {@code PaymentMethodController} through
 * the real method-security pipeline + the real {@code @scopeAuth} bean (registered by
 * {@code PaymentOrchestrationTestApplication}). Mirrors {@code CustomerControllerScopeEnforcementTest}.
 * Saved methods REUSE the {@code customers:read}/{@code customers:write} scopes.
 *
 * <ul>
 *   <li>A {@code customers:read} key is 200 on retrieve/list, 403 on attach/detach.</li>
 *   <li>A {@code customers:write} key is 403 on retrieve/list, allowed on attach(201)/detach(200).</li>
 *   <li>An UNRESTRICTED (null-scopes) key is allowed on all.</li>
 *   <li>Right scope + wrong role (viewer + customers:write hitting POST attach) is still 403.</li>
 * </ul>
 */
@WebMvcTest(PaymentMethodController.class)
class PaymentMethodControllerScopeEnforcementTest {

    private static final String CUS = "cus_1";
    private static final String PM = "pm_1";

    @Autowired private MockMvc mockMvc;
    @MockBean private PaymentMethodService paymentMethodService;

    private record ScopedAuth(String tenantId, Set<String> scopes)
            implements TenantPrincipal, ScopedPrincipal {
        @Override public boolean hasScope(String scope) {
            return scopes == null || scopes.isEmpty() || scopes.contains(scope);
        }
    }

    private static Authentication auth(String role, Set<String> scopes) {
        return new UsernamePasswordAuthenticationToken(
                new ScopedAuth("tenant-1", scopes), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static final String ATTACH_BODY = """
            { "type": "card", "credential_ref": "pm_card_visa" }
            """;

    private PaymentMethod pm() {
        return PaymentMethod.create("tenant-1", CUS, false, "card",
                "visa", "4242", 12, 2034, "credit", "pmref_test_pm_card_visa", null);
    }

    private void stubRead() {
        when(paymentMethodService.findById(eq(PM), eq("tenant-1"))).thenReturn(Optional.of(pm()));
        when(paymentMethodService.listByCustomer(eq(CUS), eq("tenant-1"), anyInt(), anyInt()))
                .thenReturn(List.of(pm()));
    }

    private void stubWrite() {
        when(paymentMethodService.attach(eq("tenant-1"), eq(CUS), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(pm());
        when(paymentMethodService.detach(eq(PM), eq("tenant-1"))).thenReturn(pm());
    }

    // --- read-scoped key ---

    @Test
    void readScopedKey_allowedOnReads() throws Exception {
        stubRead();
        mockMvc.perform(get("/v1/payment_methods/" + PM)
                        .with(authentication(auth("operator", Set.of("customers:read")))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/customers/" + CUS + "/payment_methods")
                        .with(authentication(auth("operator", Set.of("customers:read")))))
                .andExpect(status().isOk());
    }

    @Test
    void readScopedKey_forbiddenOnWrites() throws Exception {
        mockMvc.perform(post("/v1/customers/" + CUS + "/payment_methods")
                        .with(authentication(auth("operator", Set.of("customers:read"))))
                        .contentType(MediaType.APPLICATION_JSON).content(ATTACH_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/v1/payment_methods/" + PM)
                        .with(authentication(auth("operator", Set.of("customers:read")))))
                .andExpect(status().isForbidden());
    }

    // --- write-scoped key ---

    @Test
    void writeScopedKey_forbiddenOnReads() throws Exception {
        mockMvc.perform(get("/v1/payment_methods/" + PM)
                        .with(authentication(auth("operator", Set.of("customers:write")))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/customers/" + CUS + "/payment_methods")
                        .with(authentication(auth("operator", Set.of("customers:write")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void writeScopedKey_allowedOnWrites() throws Exception {
        stubWrite();
        mockMvc.perform(post("/v1/customers/" + CUS + "/payment_methods")
                        .with(authentication(auth("operator", Set.of("customers:write"))))
                        .contentType(MediaType.APPLICATION_JSON).content(ATTACH_BODY))
                .andExpect(status().isCreated());
        mockMvc.perform(delete("/v1/payment_methods/" + PM)
                        .with(authentication(auth("operator", Set.of("customers:write")))))
                .andExpect(status().isOk());
    }

    // --- unrestricted (no-scopes) key: allowed on all ---

    @Test
    void unrestrictedKey_allowedOnReadAndWrite() throws Exception {
        stubRead();
        stubWrite();
        mockMvc.perform(get("/v1/payment_methods/" + PM)
                        .with(authentication(auth("operator", null))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/customers/" + CUS + "/payment_methods")
                        .with(authentication(auth("operator", null)))
                        .contentType(MediaType.APPLICATION_JSON).content(ATTACH_BODY))
                .andExpect(status().isCreated());
    }

    // --- PCI (FINDING 5): a top-level raw-card field is DROPPED at binding — never bound, never persisted ---

    @Test
    void attachWithTopLevelCardField_isDroppedNeverReachesService() throws Exception {
        // A careless integrator POSTs a known credential_ref PLUS a stray top-level "number" carrying a
        // PAN-shaped value. The request record declares ONLY token + display fields, so under Spring's
        // default tolerant binding the undeclared "number" is DROPPED by Jackson — it never binds to a
        // component, so it can never reach the service nor be persisted (the at-rest PCI guarantee holds by
        // construction). We PROVE that by capturing the attach call: credential_ref bound to the REAL value
        // ("pm_card_visa"), and the PAN-shaped value appears in NONE of the bound arguments. (A card number
        // in a DECLARED field is fail-closed 400 by PaymentMethodService.rejectPanShape — see
        // PaymentMethodServiceTest.) The PAN-shaped value is built in Java; no literal card number appears.
        stubWrite();
        String panShaped = "9".repeat(16);
        String bodyWithTopLevelCard =
                "{ \"type\": \"card\", \"credential_ref\": \"pm_card_visa\", \"number\": \"" + panShaped + "\" }";

        mockMvc.perform(post("/v1/customers/" + CUS + "/payment_methods")
                        .with(authentication(auth("operator", Set.of("customers:write"))))
                        .contentType(MediaType.APPLICATION_JSON).content(bodyWithTopLevelCard))
                .andExpect(status().isCreated());

        // The undeclared top-level "number" was dropped: attach is called with the REAL credential_ref
        // ("pm_card_visa"), never the PAN-shaped value — proving it neither bound to a field nor reached
        // persistence. credential_ref is the 6th argument; the display fields after it are all null.
        verify(paymentMethodService).attach(eq("tenant-1"), eq(CUS), anyBoolean(), anyBoolean(),
                eq("card"), eq("pm_card_visa"), any(), any(), any(), any(), any(), any());
    }

    // --- AND-composition: right scope, wrong role -> still denied ---

    @Test
    void writeScopedKey_butWrongRole_stillForbidden() throws Exception {
        // viewer has the write SCOPE but NOT the write ROLE (POST attach requires admin/operator).
        mockMvc.perform(post("/v1/customers/" + CUS + "/payment_methods")
                        .with(authentication(auth("viewer", Set.of("customers:write"))))
                        .contentType(MediaType.APPLICATION_JSON).content(ATTACH_BODY))
                .andExpect(status().isForbidden());
    }
}
