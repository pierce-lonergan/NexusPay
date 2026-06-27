package io.nexuspay.payment.adapter.in.rest;

import io.nexuspay.common.tenant.ScopedPrincipal;
import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.payment.application.service.customer.CustomerService;
import io.nexuspay.payment.domain.customer.Customer;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TEST-3a: end-to-end {@code @PreAuthorize} scope enforcement on CustomerController through the real
 * method-security pipeline + the real {@code @scopeAuth} bean ({@code PaymentOrchestrationTestApplication}
 * registers it). Mirrors {@code PayoutControllerScopeEnforcementTest}. Guards are
 * {@code hasAnyRole(...) and @scopeAuth.has('customers:read'|'customers:write')}.
 *
 * <ul>
 *   <li>A key scoped {@code customers:read} is 200 on the read endpoints, 403 on the write endpoints.</li>
 *   <li>A key scoped {@code customers:write} is 403 on the read endpoints, allowed on the writes.</li>
 *   <li>An UNRESTRICTED (null-scopes) key is allowed on all (back-compat).</li>
 *   <li>Right scope + wrong role (viewer + customers:write hitting POST) is still 403 (AND-composition).</li>
 * </ul>
 */
@WebMvcTest(CustomerController.class)
class CustomerControllerScopeEnforcementTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private CustomerService customerService;

    /** A scope-bearing principal (mirrors NexusPayPrincipal's contract without importing iam). */
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

    private static final String BODY = """
            { "email": "jane@example.com", "name": "Jane" }
            """;

    private void stubRead() {
        Customer c = Customer.create("tenant-1", false, "jane@example.com", "Jane", null, null);
        when(customerService.findById(eq("cus_1"), eq("tenant-1"))).thenReturn(Optional.of(c));
        when(customerService.listByTenant(eq("tenant-1"), anyInt(), anyInt())).thenReturn(List.of(c));
    }

    private void stubWrite() {
        Customer c = Customer.create("tenant-1", false, "jane@example.com", "Jane", null, null);
        when(customerService.create(eq("tenant-1"), anyBoolean(), any(), any(), any(), any())).thenReturn(c);
        when(customerService.update(eq("cus_1"), eq("tenant-1"), any(), any(), any(), any())).thenReturn(c);
        when(customerService.delete(eq("cus_1"), eq("tenant-1"))).thenReturn(c);
    }

    // --- read-scoped key ---

    @Test
    void readScopedKey_allowedOnReads() throws Exception {
        stubRead();
        mockMvc.perform(get("/v1/customers/cus_1")
                        .with(authentication(auth("operator", Set.of("customers:read")))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/customers")
                        .with(authentication(auth("operator", Set.of("customers:read")))))
                .andExpect(status().isOk());
    }

    @Test
    void readScopedKey_forbiddenOnWrites() throws Exception {
        mockMvc.perform(post("/v1/customers")
                        .with(authentication(auth("operator", Set.of("customers:read"))))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/customers/cus_1")
                        .with(authentication(auth("operator", Set.of("customers:read"))))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/v1/customers/cus_1")
                        .with(authentication(auth("operator", Set.of("customers:read")))))
                .andExpect(status().isForbidden());
    }

    // --- write-scoped key ---

    @Test
    void writeScopedKey_forbiddenOnReads() throws Exception {
        mockMvc.perform(get("/v1/customers/cus_1")
                        .with(authentication(auth("operator", Set.of("customers:write")))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/customers")
                        .with(authentication(auth("operator", Set.of("customers:write")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void writeScopedKey_allowedOnWrites() throws Exception {
        stubWrite();
        mockMvc.perform(post("/v1/customers")
                        .with(authentication(auth("operator", Set.of("customers:write"))))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/customers/cus_1")
                        .with(authentication(auth("operator", Set.of("customers:write"))))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/v1/customers/cus_1")
                        .with(authentication(auth("operator", Set.of("customers:write")))))
                .andExpect(status().isOk());
    }

    // --- unrestricted (no-scopes) key: allowed on all (back-compat) ---

    @Test
    void unrestrictedKey_allowedOnReadAndWrite() throws Exception {
        stubRead();
        stubWrite();
        mockMvc.perform(get("/v1/customers/cus_1")
                        .with(authentication(auth("operator", null))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/customers")
                        .with(authentication(auth("operator", null)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());
    }

    // --- AND-composition: right scope, wrong role -> still denied ---

    @Test
    void writeScopedKey_butWrongRole_stillForbidden() throws Exception {
        // viewer has the write SCOPE but NOT the write ROLE (POST requires admin/operator).
        mockMvc.perform(post("/v1/customers")
                        .with(authentication(auth("viewer", Set.of("customers:write"))))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }
}
