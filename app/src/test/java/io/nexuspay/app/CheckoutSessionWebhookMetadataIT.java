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

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INT-2 Invariant 4 (B-029): the SDK confirm chokepoint achieves webhook-metadata parity with
 * {@code /v1/payments}. The CheckoutController confirm wiring routes through the @Primary
 * GatedPaymentGateway, which persists the INT-1 {@code payment_webhook_metadata} row keyed by the
 * gateway payment id under the SERVER-derived trusted tenant.
 *
 * <p>This IT mirrors {@code WebhookCanonicalDeliveryIT} §2 on real Postgres: it verifies that the
 * INT-1 store the confirm path targets round-trips the server-stored session correlation metadata
 * ({@code userId}/{@code packId}), strips PAN/card material, and is tenant-isolated at the app layer
 * (cross-tenant read → {@code {}}). The byte-for-byte HTTP confirm-to-PSP path is exercised in the
 * gateway-api slice ({@code CheckoutConfirmMetadataParityTest}); a full-boot confirm is not driven
 * here because there is no HyperSwitch container (the PSP delegate would fail), exactly as
 * {@code WebhookCanonicalDeliveryIT} documents.</p>
 */
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@DisplayName("INT-2: SDK checkout-confirm webhook-metadata parity (store round-trip)")
class CheckoutSessionWebhookMetadataIT extends IntegrationTestBase {

    @Autowired(required = false)
    private WebhookMetadataService webhookMetadataService;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DOCKER_AVAILABLE,
                "Docker unavailable — INT-2 checkout-metadata parity IT self-skips (Testcontainers required)");
    }

    @Test
    @DisplayName("session metadata round-trips through the INT-1 store, strips PAN, and is tenant-isolated")
    void sessionMetadata_roundTrips_andIsTenantIsolated() {
        assertThat(webhookMetadataService).isNotNull();

        // The gateway payment id a confirm would produce for this session; absent before confirm.
        String gatewayPaymentId = "pay_int2_checkout_a";
        assertThat(webhookMetadataService.find(gatewayPaymentId, "tenant-A")).isEmpty();

        // The SERVER-STORED session metadata the confirm wiring forwards (with forbidden card material
        // that must be stripped, exactly like the /v1/payments path).
        Map<String, Object> sessionMeta = new LinkedHashMap<>();
        sessionMeta.put("userId", "u_42");
        sessionMeta.put("packId", "gold");
        sessionMeta.put("payment_method_data", Map.of("number", "4111111111111111"));

        // The @Primary GatedPaymentGateway.doCreate calls exactly this (id, trusted tenant, session meta).
        webhookMetadataService.record(gatewayPaymentId, "tenant-A", sessionMeta);

        Map<String, Object> found = webhookMetadataService.find(gatewayPaymentId, "tenant-A");
        assertThat(found).containsEntry("userId", "u_42").containsEntry("packId", "gold");
        assertThat(found).doesNotContainKey("payment_method_data");

        // SEC (B-029): a different tenant cannot read this row (app-level guard, RLS-independent).
        assertThat(webhookMetadataService.find(gatewayPaymentId, "other-tenant"))
                .as("cross-tenant read of the confirm's metadata must return {}")
                .isEmpty();
    }
}
