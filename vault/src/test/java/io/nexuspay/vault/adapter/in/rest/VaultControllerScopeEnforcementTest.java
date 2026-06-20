package io.nexuspay.vault.adapter.in.rest;

import io.nexuspay.common.tenant.ScopedPrincipal;
import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.vault.application.port.in.GenerateCryptogramUseCase;
import io.nexuspay.vault.application.port.in.MigrateVaultUseCase;
import io.nexuspay.vault.application.port.in.ProvisionNetworkTokenUseCase;
import io.nexuspay.vault.application.port.in.VaultCardUseCase;
import io.nexuspay.vault.domain.CardBrand;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DX-5c-ii: end-to-end {@code @PreAuthorize} scope enforcement on {@code VaultController} through the REAL
 * method-security pipeline + the real {@code @scopeAuth} bean ({@code VaultTestApplication} registers it).
 * The vault counterpart of {@code PayoutControllerScopeEnforcementTest}: proves the {@code vault:read} /
 * {@code vault:write} guards actually FIRE a 403, and that a READ no longer requires {@code vault:write}.
 *
 * <ul>
 *   <li>{@code GET /v1/vault/cards/{token}} → {@code vault:read}</li>
 *   <li>{@code POST /v1/vault/cards} → {@code vault:write}</li>
 * </ul>
 *
 * <p>Asserts: a {@code vault:read}-scoped key is ALLOWED on the read but FORBIDDEN (403) on the write; a
 * {@code vault:write}-scoped key is allowed on the write; an UNRESTRICTED key is allowed on BOTH
 * (back-compat); and the scope check is AND-composed with the role (right scope, wrong role → 403).</p>
 */
@WebMvcTest(VaultController.class)
class VaultControllerScopeEnforcementTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private VaultCardUseCase vaultCardUseCase;
    @MockBean private ProvisionNetworkTokenUseCase provisionUseCase;
    @MockBean private GenerateCryptogramUseCase cryptogramUseCase;
    @MockBean private MigrateVaultUseCase migrateUseCase;

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

    private static final String VAULT_BODY = """
            { "pan": "4242424242424242", "expMonth": 12, "expYear": 2030, "cardholderName": "A B" }
            """;

    private void stubGetCard() {
        when(vaultCardUseCase.getCard(eq("tok_1"), eq("tenant-1"))).thenReturn(
                new VaultCardUseCase.VaultedCardInfo("tok_1", "4242", "424242",
                        CardBrand.VISA, 12, 2030, "A B", Instant.now()));
    }

    private void stubVaultCard() {
        when(vaultCardUseCase.vaultCard(any())).thenReturn(
                new VaultCardUseCase.VaultCardResult("tok_1", "4242", CardBrand.VISA, "fp_1"));
    }

    // --- read-scoped key: allowed on read, forbidden on write ---

    @Test
    void readScopedKey_allowedOnReadEndpoint() throws Exception {
        stubGetCard();
        mockMvc.perform(get("/v1/vault/cards/tok_1")
                        .with(authentication(auth("operator", Set.of("vault:read")))))
                .andExpect(status().isOk());
    }

    @Test
    void readScopedKey_forbiddenOnWriteEndpoint() throws Exception {
        mockMvc.perform(post("/v1/vault/cards")
                        .with(authentication(auth("operator", Set.of("vault:read"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VAULT_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void writeScopedKey_allowedOnWriteEndpoint() throws Exception {
        stubVaultCard();
        mockMvc.perform(post("/v1/vault/cards")
                        .with(authentication(auth("operator", Set.of("vault:write"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VAULT_BODY))
                .andExpect(status().isCreated());
    }

    // --- unrestricted (no-scopes) key: allowed on both (back-compat) ---

    @Test
    void unrestrictedKey_allowedOnReadAndWrite() throws Exception {
        stubGetCard();
        mockMvc.perform(get("/v1/vault/cards/tok_1")
                        .with(authentication(auth("operator", null))))
                .andExpect(status().isOk());

        stubVaultCard();
        mockMvc.perform(post("/v1/vault/cards")
                        .with(authentication(auth("operator", null)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VAULT_BODY))
                .andExpect(status().isCreated());
    }

    // --- AND-composition with role: right scope, wrong role -> still denied ---

    @Test
    void writeScopedKey_butWrongRole_stillForbidden() throws Exception {
        // viewer has the write SCOPE but NOT the write ROLE (POST requires admin/operator). The AND
        // composition denies: scopes NARROW the role, they never grant a role the key lacks.
        mockMvc.perform(post("/v1/vault/cards")
                        .with(authentication(auth("viewer", Set.of("vault:write"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VAULT_BODY))
                .andExpect(status().isForbidden());
    }
}
