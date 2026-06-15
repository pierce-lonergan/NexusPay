package io.nexuspay.app.redteam;

import io.nexuspay.app.IntegrationTestBase;
import io.nexuspay.app.config.TestSecurityConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * RED-TEAM (report-only, {@code @Tag("redteam")}): unauthenticated + replayable
 * dispute webhook that moves the chargeback ledger (B-001).
 *
 * <p><strong>Attack:</strong> POST a {@code dispute.opened} body to
 * {@code /internal/webhooks/disputes} with NO signature, then fire the IDENTICAL
 * event a second time. A SECURE handler (a) REJECTS the unsigned request (401,
 * constant-time HMAC verification), and (b) is IDEMPOTENT on the external dispute
 * id, so a replay books NO second chargeback reserve.</p>
 *
 * <p><strong>Why this FAILS on current main (excluded + report-only):</strong>
 * {@code DisputeWebhookHandler} is {@code permitAll}, performs no HMAC check, and
 * has no inbound-event idempotency guard — an attacker can forge a dispute and
 * replay it to move the chargeback reserve repeatedly. When the SEC fix lands
 * (HMAC + idempotency), drop {@code @Tag("redteam")} to gate this.</p>
 */
@Tag("redteam")
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@DisplayName("RED-TEAM: dispute webhook forgery + replay (B-001)")
class DisputeWebhookAuthReplayRedteamTest extends IntegrationTestBase {

    private static final String DISPUTE_BODY = """
            {
              "event_type": "dispute.opened",
              "payment_id": "pay_redteam_dispute",
              "external_dispute_id": "dp_ext_redteam_001",
              "reason_code": "FRAUD",
              "reason_description": "forged chargeback",
              "amount": 250000,
              "currency": "USD",
              "network": "VISA"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DOCKER_AVAILABLE,
                "Docker unavailable — dispute webhook red-team self-skips (Testcontainers required)");
    }

    @Test
    @DisplayName("an UNSIGNED dispute webhook is rejected (401), not processed")
    void unsignedDisputeWebhook_isRejected() throws Exception {
        mockMvc.perform(post("/internal/webhooks/disputes")
                        .header("X-Tenant-Id", "victimTenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DISPUTE_BODY))
                .andExpect(result ->
                        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                                .as("an unsigned/forged dispute webhook must be rejected, not accepted")
                                .isIn(401, 403));
    }

    @Test
    @DisplayName("a REPLAYED identical dispute event books no second chargeback reserve")
    void replayedDisputeWebhook_doesNotDoubleBookChargeback() throws Exception {
        // Fire the identical event twice. Even if (hypothetically) signed, the SECURE
        // handler must be idempotent on external_dispute_id: the second delivery must
        // NOT create a second dispute / second chargeback reserve. We assert the
        // SECOND call is treated as a duplicate (2xx idempotent ack OR 409 conflict)
        // and never as a fresh "created".
        mockMvc.perform(post("/internal/webhooks/disputes")
                .header("X-Tenant-Id", "victimTenant")
                .contentType(MediaType.APPLICATION_JSON)
                .content(DISPUTE_BODY));

        mockMvc.perform(post("/internal/webhooks/disputes")
                        .header("X-Tenant-Id", "victimTenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DISPUTE_BODY))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    // SECURE: a replay is NOT a fresh creation. On main the handler
                    // returns {"status":"created", ...} on EVERY call → this fails,
                    // surfacing the missing replay guard (report-only).
                    org.assertj.core.api.Assertions.assertThat(body)
                            .as("replayed dispute must not be re-created (no double chargeback reserve)")
                            .doesNotContain("\"status\":\"created\"");
                });
    }
}
