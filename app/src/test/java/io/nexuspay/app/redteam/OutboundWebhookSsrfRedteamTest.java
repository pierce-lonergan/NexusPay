package io.nexuspay.app.redteam;

import io.nexuspay.app.IntegrationTestBase;
import io.nexuspay.app.config.TestSecurityConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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
 * RED-TEAM (report-only, {@code @Tag("redteam")}): outbound-webhook SSRF — no
 * egress filter on the registered target URL.
 *
 * <p><strong>Attack:</strong> register an outbound webhook endpoint whose URL
 * points at an internal/link-local/loopback address (the cloud metadata service,
 * {@code localhost}, RFC-1918, etc.). When NexusPay later delivers an event, it
 * makes a server-side request to attacker-chosen internal infrastructure — classic
 * SSRF (credential theft via {@code 169.254.169.254}, internal port scans, etc.).
 * A SECURE system REJECTS registration of a non-public target URL.</p>
 *
 * <p><strong>Why this FAILS on current main (excluded + report-only):</strong>
 * webhook-endpoint registration validates the URL shape but applies no egress
 * allow-list / private-range block, so an internal URL is accepted (201). When the
 * SSRF egress-filter PR lands, drop {@code @Tag("redteam")} to gate it.</p>
 */
@Tag("redteam")
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@DisplayName("RED-TEAM: outbound-webhook SSRF (internal target not refused)")
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
}
