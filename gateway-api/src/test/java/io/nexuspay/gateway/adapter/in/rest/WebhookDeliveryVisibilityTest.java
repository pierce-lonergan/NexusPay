package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookDeliveryRepository;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookEndpointRepository;
import io.nexuspay.gateway.adapter.out.persistence.WebhookDeliveryEntity;
import io.nexuspay.gateway.adapter.out.persistence.WebhookEndpointEntity;
import io.nexuspay.gateway.adapter.out.webhook.WebhookSignature;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TEST-4a (F2): owner-scoped delivery body + signature visibility, driven through the REAL method-security
 * pipeline (the {@code @scopeAuth} bean from GatewayTestApplication) + the real controller.
 *
 * <p>Asserts: the caller's OWN delivery /body returns canonical_body; /signature returns a hex signature
 * that VERIFIES against the endpoint secret (the test recomputes the expected HMAC with the same body +
 * stubbed secret and asserts equality); the response NEVER contains the secret; a foreign/missing delivery
 * → 404 (no oracle) on both routes; a key without {@code webhooks:read} → 403.</p>
 *
 * <p>L-068: the gateway-api servlet filters are excluded (same REGEX as
 * PaymentControllerScopeEnforcementTest) — they need Redis beans absent from a web slice.</p>
 */
@WebMvcTest(controllers = WebhookEndpointController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "io\\.nexuspay\\.gateway\\.adapter\\.in\\.filter\\..*"))
class WebhookDeliveryVisibilityTest {

    private static final String TENANT = "tenant-1";
    private static final String DELIVERY_ID = "whd_1";
    private static final String ENDPOINT_ID = "we_1";
    private static final String SECRET = "whsec_super_secret_value_xyz";
    private static final String CANONICAL_BODY =
            "{\"id\":\"evt_1\",\"type\":\"payment.succeeded\",\"livemode\":false,\"data\":{\"object\":{}}}";

    @Autowired private MockMvc mockMvc;
    @MockBean private JpaWebhookEndpointRepository endpointRepository;
    @MockBean private JpaWebhookDeliveryRepository deliveryRepository;

    private static Authentication auth(String role, Set<String> scopes) {
        var principal = new NexusPayPrincipal(
                "user-1", TENANT, role, NexusPayPrincipal.AuthMethod.API_KEY, null, true, scopes);
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private WebhookDeliveryEntity ownDelivery() {
        return WebhookDeliveryEntity.pending(
                DELIVERY_ID, TENANT, ENDPOINT_ID, "evt_1", "payment.succeeded", CANONICAL_BODY);
    }

    private WebhookEndpointEntity ownEndpoint() {
        return new WebhookEndpointEntity(
                ENDPOINT_ID, "https://merchant.example.com/hook", "desc", SECRET,
                List.of("*"), TENANT);
    }

    @Test
    void ownDelivery_body_returnsCanonicalBody_noSecret() throws Exception {
        when(deliveryRepository.findByIdAndTenantId(eq(DELIVERY_ID), eq(TENANT)))
                .thenReturn(Optional.of(ownDelivery()));

        mockMvc.perform(get("/v1/webhook-deliveries/{id}/body", DELIVERY_ID)
                        .with(authentication(auth("operator", Set.of("webhooks:read")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(DELIVERY_ID))
                .andExpect(jsonPath("$.endpoint_id").value(ENDPOINT_ID))
                .andExpect(jsonPath("$.canonical_body").value(CANONICAL_BODY))
                // the secret must NEVER appear anywhere in the response.
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(SECRET))));
    }

    @Test
    void ownDelivery_signature_verifiesAgainstSecret_andNeverContainsSecret() throws Exception {
        when(deliveryRepository.findByIdAndTenantId(eq(DELIVERY_ID), eq(TENANT)))
                .thenReturn(Optional.of(ownDelivery()));
        when(endpointRepository.findByIdAndTenantId(eq(ENDPOINT_ID), eq(TENANT)))
                .thenReturn(Optional.of(ownEndpoint()));

        // Compute the expected HMAC INDEPENDENTLY in the test (same body + stubbed secret) and assert
        // the endpoint returns exactly it — proving the recomputed signature verifies.
        String expected = WebhookSignature.sign(CANONICAL_BODY, SECRET);

        mockMvc.perform(get("/v1/webhook-deliveries/{id}/signature", DELIVERY_ID)
                        .with(authentication(auth("operator", Set.of("webhooks:read")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(DELIVERY_ID))
                .andExpect(jsonPath("$.endpoint_id").value(ENDPOINT_ID))
                .andExpect(jsonPath("$.algorithm").value("HmacSHA256"))
                .andExpect(jsonPath("$.signature").value(expected))
                .andExpect(jsonPath("$.rotated_secret_caveat").exists())
                // the secret must NEVER appear anywhere in the response.
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(SECRET))));
    }

    @Test
    void foreignOrMissingDelivery_body_is404NoOracle() throws Exception {
        when(deliveryRepository.findByIdAndTenantId(eq(DELIVERY_ID), eq(TENANT)))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/webhook-deliveries/{id}/body", DELIVERY_ID)
                        .with(authentication(auth("operator", Set.of("webhooks:read")))))
                .andExpect(status().isNotFound());
    }

    @Test
    void foreignOrMissingDelivery_signature_is404NoOracle() throws Exception {
        when(deliveryRepository.findByIdAndTenantId(eq(DELIVERY_ID), eq(TENANT)))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/webhook-deliveries/{id}/signature", DELIVERY_ID)
                        .with(authentication(auth("operator", Set.of("webhooks:read")))))
                .andExpect(status().isNotFound());
    }

    @Test
    void signature_unresolvableOwningEndpoint_is404NoOracle() throws Exception {
        // The delivery resolves for the tenant but its owning endpoint no longer does -> 404 (no secret to
        // sign with, never fabricate a signature).
        when(deliveryRepository.findByIdAndTenantId(eq(DELIVERY_ID), eq(TENANT)))
                .thenReturn(Optional.of(ownDelivery()));
        when(endpointRepository.findByIdAndTenantId(eq(ENDPOINT_ID), eq(TENANT)))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/webhook-deliveries/{id}/signature", DELIVERY_ID)
                        .with(authentication(auth("operator", Set.of("webhooks:read")))))
                .andExpect(status().isNotFound());
    }

    @Test
    void scopeDenial_noWebhooksRead_is403_onBody() throws Exception {
        mockMvc.perform(get("/v1/webhook-deliveries/{id}/body", DELIVERY_ID)
                        .with(authentication(auth("operator", Set.of("payments:read")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void scopeDenial_noWebhooksRead_is403_onSignature() throws Exception {
        mockMvc.perform(get("/v1/webhook-deliveries/{id}/signature", DELIVERY_ID)
                        .with(authentication(auth("operator", Set.of("payments:read")))))
                .andExpect(status().isForbidden());
    }
}
