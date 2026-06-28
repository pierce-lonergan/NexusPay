package io.nexuspay.payment.adapter.in.rest;

import io.nexuspay.common.tenant.ScopedPrincipal;
import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.payment.application.service.mandate.MandateService;
import io.nexuspay.payment.domain.mandate.Mandate;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TEST-3d: end-to-end {@code @PreAuthorize} scope enforcement on {@code MandateController} through the real
 * method-security pipeline + the real {@code @scopeAuth} bean (registered by
 * {@code PaymentOrchestrationTestApplication}). Mirrors {@code PaymentMethodControllerScopeEnforcementTest}.
 * A mandate REUSES the {@code customers:read}/{@code customers:write} scopes (NO new ApiScope — the exact-set
 * vocabulary guard stays untouched).
 *
 * <ul>
 *   <li>A {@code customers:read} key is 200 on retrieve/list, 403 on create/revoke.</li>
 *   <li>A {@code customers:write} key is 403 on retrieve/list, allowed on create(201)/revoke(200).</li>
 *   <li>An UNRESTRICTED (null-scopes) key is allowed on all.</li>
 *   <li>Right scope + wrong role (viewer + customers:write hitting POST create) is still 403.</li>
 * </ul>
 */
@WebMvcTest(MandateController.class)
class MandateControllerScopeEnforcementTest {

    private static final String CUS = "cus_1";
    private static final String PM = "pm_1";
    private static final String MANDATE = "mandate_1";

    @Autowired private MockMvc mockMvc;
    @MockBean private MandateService mandateService;

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

    private static final String CREATE_BODY = """
            { "payment_method": "pm_1", "type": "MULTI_USE" }
            """;

    private Mandate mandate() {
        return Mandate.create("tenant-1", CUS, PM, false, Mandate.TYPE_MULTI_USE, "recurring", null);
    }

    private void stubRead() {
        when(mandateService.findById(eq(MANDATE), eq("tenant-1"))).thenReturn(Optional.of(mandate()));
        when(mandateService.listByTenant(eq("tenant-1"), anyInt(), anyInt()))
                .thenReturn(List.of(mandate()));
    }

    private void stubWrite() {
        when(mandateService.create(eq("tenant-1"), anyBoolean(), anyBoolean(),
                any(), any(), any(), any())).thenReturn(mandate());
        when(mandateService.revoke(eq(MANDATE), eq("tenant-1"))).thenReturn(mandate());
    }

    // --- read-scoped key ---

    @Test
    void readScopedKey_allowedOnReads() throws Exception {
        stubRead();
        mockMvc.perform(get("/v1/mandates/" + MANDATE)
                        .with(authentication(auth("operator", Set.of("customers:read")))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/mandates")
                        .with(authentication(auth("operator", Set.of("customers:read")))))
                .andExpect(status().isOk());
    }

    @Test
    void readScopedKey_forbiddenOnWrites() throws Exception {
        mockMvc.perform(post("/v1/mandates")
                        .with(authentication(auth("operator", Set.of("customers:read"))))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/mandates/" + MANDATE + "/revoke")
                        .with(authentication(auth("operator", Set.of("customers:read")))))
                .andExpect(status().isForbidden());
    }

    // --- write-scoped key ---

    @Test
    void writeScopedKey_forbiddenOnReads() throws Exception {
        mockMvc.perform(get("/v1/mandates/" + MANDATE)
                        .with(authentication(auth("operator", Set.of("customers:write")))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/mandates")
                        .with(authentication(auth("operator", Set.of("customers:write")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void writeScopedKey_allowedOnWrites() throws Exception {
        stubWrite();
        mockMvc.perform(post("/v1/mandates")
                        .with(authentication(auth("operator", Set.of("customers:write"))))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/mandates/" + MANDATE + "/revoke")
                        .with(authentication(auth("operator", Set.of("customers:write")))))
                .andExpect(status().isOk());

        // L-072 BINDING GUARD: prove the DOCUMENTED snake_case wire key actually binds through Jackson.
        // CREATE_BODY sends { "payment_method": "pm_1", "type": "MULTI_USE" }; the request record declares the
        // pm_ component as `paymentMethodId` carrying @JsonProperty("payment_method"). We assert the create
        // call received the REAL pm_ ("pm_1") and the type ("MULTI_USE") — NOT any()/null. If
        // @JsonProperty("payment_method") were removed there is NO global snake_case strategy, so
        // payment_method would bind to null and this verify would fail CI (the proven pm template's
        // attachWithTopLevelCardField_isDroppedNeverReachesService binding assertion, mirrored here).
        verify(mandateService).create(eq("tenant-1"), anyBoolean(), anyBoolean(),
                eq("pm_1"), eq("MULTI_USE"), any(), any());
    }

    // --- unrestricted (no-scopes) key: allowed on all ---

    @Test
    void unrestrictedKey_allowedOnReadAndWrite() throws Exception {
        stubRead();
        stubWrite();
        mockMvc.perform(get("/v1/mandates/" + MANDATE)
                        .with(authentication(auth("operator", null))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/mandates")
                        .with(authentication(auth("operator", null)))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated());
    }

    // --- AND-composition: right scope, wrong role -> still denied ---

    @Test
    void writeScopedKey_butWrongRole_stillForbidden() throws Exception {
        // viewer has the write SCOPE but NOT the write ROLE (POST create requires admin/operator).
        mockMvc.perform(post("/v1/mandates")
                        .with(authentication(auth("viewer", Set.of("customers:write"))))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isForbidden());
    }
}
