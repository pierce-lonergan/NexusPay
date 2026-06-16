package io.nexuspay.app;

import com.jayway.jsonpath.JsonPath;
import io.nexuspay.app.config.TestSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-BATCH-1 end-to-end tenant-isolation proof against the real advice chain (gateway-api's
 * GlobalExceptionHandler) and the real {@code findByIdAndTenantId} SQL on a Testcontainers Postgres.
 *
 * <p>Pattern: seed a resource under tenant A, then act as tenant B → must get 404 (not 403, not 500,
 * and the row must not be mutated). Authentication carries a tenant-bearing {@link
 * io.nexuspay.iam.domain.NexusPayPrincipal} via {@link TestSecurityConfig#authForRole(String, String)}
 * — there is no X-Tenant-Id header anywhere.</p>
 */
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class TenantIsolationIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Vault card: tenant B cannot read tenant A's vaulted card (404, no PAN disclosure)")
    void vaultCard_crossTenantRead_returns404() throws Exception {
        // Seed a card under tenant-A.
        MvcResult created = mockMvc.perform(post("/v1/vault/cards")
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "pan": "4111111111111111",
                                    "expMonth": 12,
                                    "expYear": 2030,
                                    "cardholderName": "Alice A"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String token = JsonPath.read(created.getResponse().getContentAsString(), "$.token");

        // Tenant-A can read its own card.
        mockMvc.perform(get("/v1/vault/cards/" + token)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A"))))
                .andExpect(status().isOk());

        // Tenant-B reading tenant-A's card → 404 (same as truly-absent; no existence oracle).
        mockMvc.perform(get("/v1/vault/cards/" + token)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-B"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Vault card: tenant B cannot delete tenant A's card; tenant A's card survives")
    void vaultCard_crossTenantDelete_returns404_andRowSurvives() throws Exception {
        MvcResult created = mockMvc.perform(post("/v1/vault/cards")
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "pan": "5555555555554444",
                                    "expMonth": 6,
                                    "expYear": 2031,
                                    "cardholderName": "Alice A"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String token = JsonPath.read(created.getResponse().getContentAsString(), "$.token");

        // Tenant-B attempts delete → 404, no cascade.
        mockMvc.perform(delete("/v1/vault/cards/" + token)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-B"))))
                .andExpect(status().isNotFound());

        // The card still belongs to tenant-A and is readable by tenant-A — it was not deleted.
        mockMvc.perform(get("/v1/vault/cards/" + token)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Connected account: tenant B cannot read tenant A's account (404)")
    void connectedAccount_crossTenantRead_returns404() throws Exception {
        MvcResult created = mockMvc.perform(post("/v1/connected-accounts")
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessName":"Acme A","email":"a@acme.test","country":"US","defaultCurrency":"USD"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String accountId = JsonPath.read(created.getResponse().getContentAsString(), "$.accountId");

        mockMvc.perform(get("/v1/connected-accounts/" + accountId)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v1/connected-accounts/" + accountId)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-B"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Webhook endpoint: tenant B's delete silently no-ops; tenant A's endpoint survives")
    void webhookEndpoint_crossTenantDelete_noOpsAndSurvives() throws Exception {
        MvcResult created = mockMvc.perform(post("/v1/webhook-endpoints")
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "https://example.com/webhooks",
                                  "events": ["payment.succeeded"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String endpointId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        // Tenant-B delete → 204 no-op (SEC-19 keeps 204-on-miss, no existence oracle).
        mockMvc.perform(delete("/v1/webhook-endpoints/" + endpointId)
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-B"))))
                .andExpect(status().isNoContent());

        // Endpoint is still enabled for tenant-A — the cross-tenant delete did NOT disable it.
        mockMvc.perform(get("/v1/webhook-endpoints")
                        .with(authentication(TestSecurityConfig.authForRole("admin", "tenant-A"))))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[?(@.id=='" + endpointId + "')]").exists());
    }
}
