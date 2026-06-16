package io.nexuspay.app;

import io.nexuspay.app.config.TestSecurityConfig;
import io.nexuspay.payment.application.webhook.WebhookMetadataService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * INT-1 integration coverage for the canonical outbound webhook contract, on the full Spring boot:
 *
 * <ol>
 *   <li><b>Registration validator</b> — POST {@code /v1/webhook-endpoints} with an unknown event type is
 *       rejected with 400 ({@code @CanonicalWebhookEvents} → MethodArgumentNotValidException →
 *       GlobalExceptionHandler); a canonical dotted subscription is accepted with 201.</li>
 *   <li><b>Merchant-metadata store</b> — the new {@link WebhookMetadataService} + V4030
 *       {@code payment_webhook_metadata} table round-trips through real Postgres (jsonb mapping),
 *       strips PAN/card material, and returns {} when absent.</li>
 * </ol>
 *
 * <p>The byte-for-byte canonical envelope, the HMAC-over-transformed-body re-sign, and the tenant
 * isolation of delivery are verified end-to-end against a real receiver in the gateway-api unit suite
 * ({@code WebhookDeliveryServiceTest}/{@code WebhookEnvelopeSerializerTest}) — a full-boot delivery to a
 * loopback receiver is not exercised here because the production delivery guard (SEC-4b) refuses a
 * non-public target, which is the intended behaviour.</p>
 */
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@DisplayName("INT-1: canonical webhook contract (validator + metadata store)")
class WebhookCanonicalDeliveryIT extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired(required = false)
    private WebhookMetadataService webhookMetadataService;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DOCKER_AVAILABLE,
                "Docker unavailable — INT-1 canonical webhook IT self-skips (Testcontainers required)");
    }

    // ---- 1. registration validator ----

    @Test
    @DisplayName("registering an unknown event type is rejected (400)")
    void registeringUnknownEvent_isRejected() throws Exception {
        String body = """
                {
                  "url": "https://example.com/webhooks",
                  "events": ["payment.captured"]
                }
                """;
        mockMvc.perform(post("/v1/webhook-endpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(authentication(TestSecurityConfig.authFor("default", "admin"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("registering canonical dotted events is accepted (201)")
    void registeringCanonicalEvents_isAccepted() throws Exception {
        String body = """
                {
                  "url": "https://example.com/webhooks",
                  "events": ["payment.succeeded", "payment.refunded"]
                }
                """;
        mockMvc.perform(post("/v1/webhook-endpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(authentication(TestSecurityConfig.authFor("default", "admin"))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("registering the wildcard \"*\" is accepted (201)")
    void registeringWildcard_isAccepted() throws Exception {
        String body = """
                {
                  "url": "https://example.com/webhooks",
                  "events": ["*"]
                }
                """;
        mockMvc.perform(post("/v1/webhook-endpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(authentication(TestSecurityConfig.authFor("default", "admin"))))
                .andExpect(status().isCreated());
    }

    // ---- 2. merchant-metadata store round-trip (real Postgres jsonb) ----

    @Test
    @DisplayName("metadata store persists correlation keys, strips PAN, returns {} when absent")
    void metadataStore_roundTrips_andStripsPan() {
        assertThat(webhookMetadataService).isNotNull();

        // absent -> {} (reads are now tenant-scoped at the app layer: find(id, tenant)).
        assertThat(webhookMetadataService.find("pay_int1_absent", "default")).isEmpty();

        // persist a correlation map that ALSO contains forbidden card material — it must be stripped.
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("userId", "u_42");
        meta.put("packId", "gold");
        meta.put("payment_method_data", Map.of("number", "4111111111111111"));
        webhookMetadataService.record("pay_int1_a", "default", meta);

        Map<String, Object> found = webhookMetadataService.find("pay_int1_a", "default");
        assertThat(found).containsEntry("userId", "u_42").containsEntry("packId", "gold");
        assertThat(found).doesNotContainKey("payment_method_data");

        // SEC (INT-1): a different tenant cannot read this row, even with RLS dormant (app-level guard).
        assertThat(webhookMetadataService.find("pay_int1_a", "other-tenant"))
                .as("cross-tenant read of another tenant's metadata must return {}")
                .isEmpty();

        // idempotent: a second record with different data is a no-op.
        webhookMetadataService.record("pay_int1_a", "default", Map.of("userId", "changed"));
        assertThat(webhookMetadataService.find("pay_int1_a", "default")).containsEntry("userId", "u_42");
    }
}
