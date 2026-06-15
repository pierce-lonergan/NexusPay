package io.nexuspay.vault.adapter.in.rest;

import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.vault.application.port.in.GenerateCryptogramUseCase;
import io.nexuspay.vault.application.port.in.MigrateVaultUseCase;
import io.nexuspay.vault.application.port.in.ProvisionNetworkTokenUseCase;
import io.nexuspay.vault.application.port.in.ProvisionNetworkTokenUseCase.NetworkTokenResult;
import io.nexuspay.vault.application.port.in.VaultCardUseCase;
import io.nexuspay.vault.application.port.in.VaultCardUseCase.VaultCardResult;
import io.nexuspay.vault.application.port.in.VaultCardUseCase.VaultedCardInfo;
import io.nexuspay.vault.domain.CardBrand;
import io.nexuspay.vault.domain.MigrationStatus;
import io.nexuspay.vault.domain.NetworkType;
import io.nexuspay.vault.domain.TokenState;
import io.nexuspay.vault.domain.VaultMigration;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VaultController.class)
class VaultControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VaultCardUseCase vaultCardUseCase;

    @MockBean
    private ProvisionNetworkTokenUseCase provisionUseCase;

    @MockBean
    private GenerateCryptogramUseCase cryptogramUseCase;

    @MockBean
    private MigrateVaultUseCase migrateUseCase;

    /**
     * Builds a tenant-bearing authentication WITHOUT importing the iam principal. The slice can
     * construct this because {@link TenantPrincipal} lives in {@code common}; {@code CallerTenant}
     * resolves the tenant from it exactly as in production. Replaces the old {@code @WithMockUser} +
     * {@code X-Tenant-Id} header combination (the header is gone; the principal now carries tenant).
     */
    private static Authentication tenantAuth(String tenantId, String role) {
        TenantPrincipal principal = () -> tenantId;
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void postCard_returns201_withToken() throws Exception {
        VaultCardResult result = new VaultCardResult("tok_abc123", "1111", CardBrand.VISA, "fp_xyz");
        when(vaultCardUseCase.vaultCard(any(VaultCardUseCase.VaultCardCommand.class))).thenReturn(result);

        mockMvc.perform(post("/v1/vault/cards")
                        .with(authentication(tenantAuth("tenant-1", "admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "pan": "4111111111111111",
                                    "expMonth": 12,
                                    "expYear": 2028,
                                    "cardholderName": "John Doe"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("tok_abc123"))
                .andExpect(jsonPath("$.panLast4").value("1111"))
                .andExpect(jsonPath("$.brand").value("VISA"))
                .andExpect(jsonPath("$.fingerprint").value("fp_xyz"));
    }

    @Test
    void getCard_returns200() throws Exception {
        VaultedCardInfo info = new VaultedCardInfo(
                "tok_123", "1111", "41111111", CardBrand.VISA,
                12, 2028, "John Doe", Instant.parse("2026-01-15T10:00:00Z"));
        // Controller passes the principal tenant ("tenant-1"), not a header.
        when(vaultCardUseCase.getCard(eq("tok_123"), eq("tenant-1"))).thenReturn(info);

        mockMvc.perform(get("/v1/vault/cards/tok_123")
                        .with(authentication(tenantAuth("tenant-1", "admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("tok_123"))
                .andExpect(jsonPath("$.panLast4").value("1111"))
                .andExpect(jsonPath("$.brand").value("VISA"))
                .andExpect(jsonPath("$.cardholderName").value("John Doe"));
    }

    @Test
    void deleteCard_returns204() throws Exception {
        mockMvc.perform(delete("/v1/vault/cards/tok_123")
                        .with(authentication(tenantAuth("tenant-1", "admin"))))
                .andExpect(status().isNoContent());

        // The principal now carries tenant-1, so the use case is invoked with it.
        verify(vaultCardUseCase).deleteCard(eq("tok_123"), eq("tenant-1"));
    }

    @Test
    void provisionNetworkToken_returns201() throws Exception {
        NetworkTokenResult result = new NetworkTokenResult("nt_001", "7890", TokenState.PROVISIONED, NetworkType.VISA_VTS);
        when(provisionUseCase.provision(eq("tok_123"), eq("tenant-1"), any(NetworkType.class))).thenReturn(result);

        mockMvc.perform(post("/v1/vault/cards/tok_123/network-tokens")
                        .with(authentication(tenantAuth("tenant-1", "admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "network": "VISA_VTS"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.networkTokenId").value("nt_001"))
                .andExpect(jsonPath("$.tokenLast4").value("7890"))
                .andExpect(jsonPath("$.status").value("PROVISIONED"))
                .andExpect(jsonPath("$.network").value("VISA_VTS"));
    }

    @Test
    void startMigration_returns201() throws Exception {
        VaultMigration migration = new VaultMigration();
        migration.setId("vm_001");
        migration.setStatus(MigrationStatus.PENDING);
        migration.setSourceProvider("spreedly");
        migration.setTotalCards(5000);
        migration.setMigratedCount(0);
        migration.setFailedCount(0);

        when(migrateUseCase.startMigration(eq("tenant-1"), eq("spreedly"), eq(5000))).thenReturn(migration);

        mockMvc.perform(post("/v1/vault/migrations")
                        .with(authentication(tenantAuth("tenant-1", "admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "sourceProvider": "spreedly",
                                    "totalCards": 5000
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("vm_001"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.sourceProvider").value("spreedly"))
                .andExpect(jsonPath("$.totalCards").value(5000));
    }

    @Test
    void getCard_ignoresClientHeader_usesPrincipalTenant() throws Exception {
        // SEC-05/06: an attacker-supplied X-Tenant-Id must NOT influence tenant scoping. Even when the
        // header claims "tenant-evil", the controller resolves the principal's tenant ("tenant-1").
        VaultedCardInfo info = new VaultedCardInfo(
                "tok_123", "1111", "41111111", CardBrand.VISA,
                12, 2028, "John Doe", Instant.parse("2026-01-15T10:00:00Z"));
        when(vaultCardUseCase.getCard(eq("tok_123"), eq("tenant-1"))).thenReturn(info);

        mockMvc.perform(get("/v1/vault/cards/tok_123")
                        .header("X-Tenant-Id", "tenant-evil")
                        .with(authentication(tenantAuth("tenant-1", "admin"))))
                .andExpect(status().isOk());

        verify(vaultCardUseCase).getCard(eq("tok_123"), eq("tenant-1"));
    }

    @Test
    void postCard_noAuth_returns401() throws Exception {
        mockMvc.perform(post("/v1/vault/cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "pan": "4111111111111111",
                                    "expMonth": 12,
                                    "expYear": 2028,
                                    "cardholderName": "John Doe"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }
}
