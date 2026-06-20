package io.nexuspay.marketplace.adapter.in.rest;

import io.nexuspay.common.tenant.ScopedPrincipal;
import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.marketplace.application.port.in.SchedulePayoutUseCase;
import io.nexuspay.marketplace.domain.PayoutMethod;
import io.nexuspay.marketplace.domain.PayoutStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DX-5c-ii: end-to-end @PreAuthorize scope enforcement through the real method-security pipeline + the
 * real {@code @scopeAuth} bean ({@code MarketplaceTestApplication} registers it). PayoutController guards
 * are {@code hasAnyRole(...) and @scopeAuth.has('payouts:read'|'payouts:write')}.
 *
 * <ul>
 *   <li>A key scoped to {@code payouts:read} is FORBIDDEN (403) on the write endpoint, ALLOWED on read.</li>
 *   <li>An UNRESTRICTED (no-scopes) key is allowed on BOTH (back-compat).</li>
 *   <li>The scope check is AND-composed with the role: a key with the right scope but the WRONG role is
 *       still denied (403).</li>
 * </ul>
 */
@WebMvcTest(PayoutController.class)
class PayoutControllerScopeEnforcementTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private SchedulePayoutUseCase payoutUseCase;

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

    private static final String CREATE_BODY = """
            {
              "connectedAccountId": "ca_1",
              "amount": 5000,
              "currency": "USD",
              "method": "BANK_TRANSFER",
              "scheduledAt": null
            }
            """;

    private void stubGet() {
        when(payoutUseCase.getPayout(eq("po_1"), eq("tenant-1"))).thenReturn(
                new SchedulePayoutUseCase.PayoutResult("po_1", "ca_1", 5000, "USD",
                        PayoutStatus.PENDING, PayoutMethod.BANK_TRANSFER, null, null, null, null, Instant.now()));
    }

    private void stubCreate() {
        when(payoutUseCase.createPayout(any())).thenReturn(
                new SchedulePayoutUseCase.PayoutResult("po_1", "ca_1", 5000, "USD",
                        PayoutStatus.PENDING, PayoutMethod.BANK_TRANSFER, null, null, null, null, Instant.now()));
    }

    // --- read-scoped key: allowed on read, forbidden on write ---

    @Test
    void readScopedKey_allowedOnReadEndpoint() throws Exception {
        stubGet();
        mockMvc.perform(get("/v1/payouts/po_1")
                        .with(authentication(auth("operator", Set.of("payouts:read")))))
                .andExpect(status().isOk());
        verify(payoutUseCase).getPayout("po_1", "tenant-1");
    }

    @Test
    void readScopedKey_forbiddenOnWriteEndpoint() throws Exception {
        mockMvc.perform(post("/v1/payouts")
                        .with(authentication(auth("operator", Set.of("payouts:read"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isForbidden());
    }

    // --- unrestricted (no-scopes) key: allowed on both (back-compat) ---

    @Test
    void unrestrictedKey_allowedOnReadAndWrite() throws Exception {
        stubGet();
        mockMvc.perform(get("/v1/payouts/po_1")
                        .with(authentication(auth("operator", null))))
                .andExpect(status().isOk());

        stubCreate();
        mockMvc.perform(post("/v1/payouts")
                        .with(authentication(auth("operator", null)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated());
    }

    // --- AND-composition with role: right scope, wrong role -> still denied ---

    @Test
    void writeScopedKey_butWrongRole_stillForbidden() throws Exception {
        // viewer has the write SCOPE but NOT the write ROLE (POST requires admin/operator). The AND
        // composition denies: scopes NARROW the role, they never grant a role the key lacks.
        mockMvc.perform(post("/v1/payouts")
                        .with(authentication(auth("viewer", Set.of("payouts:write"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void writeScopedKey_correctRole_allowedOnWrite() throws Exception {
        stubCreate();
        mockMvc.perform(post("/v1/payouts")
                        .with(authentication(auth("operator", Set.of("payouts:write"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated());
    }
}
