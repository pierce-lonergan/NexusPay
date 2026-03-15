package io.nexuspay.app;

import io.nexuspay.app.config.TestSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for webhook endpoint management.
 */
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class WebhookEndpointIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Webhook endpoint CRUD lifecycle")
    void webhookEndpoint_crudLifecycle() throws Exception {
        // Create
        var createResult = mockMvc.perform(post("/v1/webhook-endpoints")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                TestSecurityConfig.authForRole("admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "https://merchant.example.com/webhooks",
                                  "description": "Test endpoint",
                                  "events": ["payment.captured", "refund.completed"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.url").value("https://merchant.example.com/webhooks"))
                .andExpect(jsonPath("$.secret").exists())
                .andExpect(jsonPath("$.events").isArray())
                .andReturn();

        String responseBody = createResult.getResponse().getContentAsString();
        String endpointId = com.jayway.jsonpath.JsonPath.read(responseBody, "$.id");

        // List
        mockMvc.perform(get("/v1/webhook-endpoints")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                TestSecurityConfig.authForRole("admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + endpointId + "')]").exists())
                .andExpect(jsonPath("$[?(@.id=='" + endpointId + "')].secret").doesNotExist());

        // Delete
        mockMvc.perform(delete("/v1/webhook-endpoints/" + endpointId)
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                TestSecurityConfig.authForRole("admin"))))
                .andExpect(status().isNoContent());

        // Verify deleted (soft delete)
        mockMvc.perform(get("/v1/webhook-endpoints")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                TestSecurityConfig.authForRole("admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + endpointId + "')]").doesNotExist());
    }

    @Test
    @DisplayName("Operator cannot create webhook endpoints")
    void webhookEndpoint_operatorCannotCreate() throws Exception {
        mockMvc.perform(post("/v1/webhook-endpoints")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                TestSecurityConfig.authForRole("operator")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "https://test.com/hooks",
                                  "events": ["payment.captured"]
                                }
                                """))
                .andExpect(status().isForbidden());
    }
}
