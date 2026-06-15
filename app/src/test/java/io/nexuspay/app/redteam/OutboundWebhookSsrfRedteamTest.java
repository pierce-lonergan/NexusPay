package io.nexuspay.app.redteam;

import io.nexuspay.app.IntegrationTestBase;
import io.nexuspay.app.config.TestSecurityConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * GATING (SEC-14): outbound-webhook SSRF — registration must REFUSE a non-public target URL.
 *
 * <p><strong>Attack:</strong> register an outbound webhook endpoint whose URL
 * points at an internal/link-local/loopback address (the cloud metadata service,
 * {@code localhost}, RFC-1918, etc.). When NexusPay later delivers an event, it
 * makes a server-side request to attacker-chosen internal infrastructure — classic
 * SSRF (credential theft via {@code 169.254.169.254}, internal port scans, etc.).
 * A SECURE system REJECTS registration of a non-public target URL.</p>
 *
 * <p><strong>SEC-14 landed — flipped INTO the gate</strong> (the {@code @Tag("redteam")} was
 * removed): {@code @SafeWebhookUrl} on {@code CreateWebhookEndpointRequest.url} now delegates to the
 * shared {@code WebhookUrlValidator}. The 7 {@code http://} vectors reject on the https-only scheme
 * check; the {@code https://metadata.google.internal/...} vector rejects either via host resolution to
 * a link-local address or, where it does not resolve in CI, fail-closed on NXDOMAIN. All surface as
 * {@code MethodArgumentNotValidException} → 400 (GlobalExceptionHandler), satisfying isIn(400,422).</p>
 *
 * <p>NOTE: this suite exercises ONLY the REGISTRATION gate. The delivery-time / DNS-rebinding half of
 * SEC-14 ({@code WebhookDeliveryService.validateAndResolve} + IP pinning) is verified by reasoning and
 * the validator's own unit cases, not by an attack here.</p>
 *
 * <p>The suite also asserts a POSITIVE case ({@link #registeringPublicHttpsTarget_succeeds()}): a
 * normal public https endpoint must be ACCEPTED (201). Without it, an over-broad validator that
 * rejected EVERYTHING would pass all the reject cases — the positive case catches over-rejection.</p>
 */
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@DisplayName("SEC-14: outbound-webhook SSRF (internal target refused at registration)")
class OutboundWebhookSsrfRedteamTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DOCKER_AVAILABLE,
                "Docker unavailable — SSRF red-team self-skips (Testcontainers required)");
    }

    @ParameterizedTest(name = "internal target URL must be refused: {0}")
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data/iam/security-credentials/",  // cloud metadata
            "http://localhost:8080/internal",                                      // loopback
            "http://127.0.0.1/admin",                                              // loopback IP
            "http://10.0.0.5/hooks",                                               // RFC-1918
            "http://192.168.1.10/hooks",                                           // RFC-1918
            "http://172.16.0.9/hooks",                                             // RFC-1918
            "http://[::1]/hooks",                                                  // IPv6 loopback
            "https://metadata.google.internal/computeMetadata/v1/"                 // GCP metadata
    })
    void registeringInternalWebhookTarget_isRefused(String internalUrl) throws Exception {
        String body = """
                {
                  "url": "%s",
                  "description": "ssrf attack target",
                  "events": ["payment.captured"]
                }
                """.formatted(internalUrl);

        mockMvc.perform(post("/v1/webhook-endpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(authentication(TestSecurityConfig.authFor("default", "admin"))))
                .andExpect(result ->
                        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                                .as("an internal/link-local webhook target must be rejected (400/422), not created (201)")
                                .isIn(400, 422));
    }

    /**
     * POSITIVE case (guards against over-rejection): registering a NORMAL public https endpoint must
     * SUCCEED (201). {@code example.com} is an IANA-reserved, stably-resolving public domain whose A/AAAA
     * records are public addresses, so {@code @SafeWebhookUrl} -> {@code WebhookUrlValidator} accepts it.
     * If the validator were over-broad (rejecting everything), this would fail — so it proves the gate
     * blocks ONLY non-public targets, not all targets.
     */
    @Test
    @DisplayName("SEC-14: a normal public https webhook target is ACCEPTED (201)")
    void registeringPublicHttpsTarget_succeeds() throws Exception {
        String body = """
                {
                  "url": "https://example.com/webhooks/payments",
                  "description": "legitimate merchant endpoint",
                  "events": ["payment.captured"]
                }
                """;

        mockMvc.perform(post("/v1/webhook-endpoints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(authentication(TestSecurityConfig.authFor("default", "admin"))))
                .andExpect(result ->
                        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                                .as("a legitimate public https webhook target must be accepted (201)")
                                .isEqualTo(201));
    }
}
