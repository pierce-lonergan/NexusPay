package io.nexuspay.app.redteam;

import io.nexuspay.app.IntegrationTestBase;
import io.nexuspay.app.config.TestSecurityConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * IN-GATE regression guard (SEC-01 / B-001) — was {@code @Tag("redteam")},
 * report-only; flipped into the default gate once SEC-BATCH-2 landed the
 * fail-closed HMAC + replay idempotency + server-authoritative tenant on the
 * dispute webhook.
 *
 * <p><strong>Attack:</strong> POST a {@code dispute.opened} body to
 * {@code /internal/webhooks/disputes}. A SECURE handler (a) REJECTS an UNSIGNED
 * request (401, constant-time HMAC-SHA512), and (b) is IDEMPOTENT on
 * {@code (tenant_id, external_dispute_id)}, so a replay of an identical SIGNED
 * event books NO second chargeback reserve — the reserve posts exactly once.</p>
 *
 * <p>The tenant is taken from the HMAC-VERIFIED body ({@code tenant_id}), not the
 * client {@code X-Tenant-Id} header — the header below is left on the requests
 * deliberately to prove it is IGNORED (SEC-BATCH-1 / L-048).</p>
 *
 * <p>Self-skips without Docker via {@link IntegrationTestBase}.</p>
 */
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@DisplayName("dispute webhook forgery + replay is rejected/deduped (B-001, in-gate)")
class DisputeWebhookAuthReplayRedteamTest extends IntegrationTestBase {

    // Matches application-test.yml: nexuspay.dispute.webhook-secret
    private static final String WEBHOOK_SECRET = "test_webhook_secret";
    private static final String HMAC_ALGO = "HmacSHA512";
    private static final String EXTERNAL_ID = "dp_ext_redteam_001";

    // The HMAC-verified body names the tenant the dispute belongs to (server
    // authority). Below, the X-Tenant-Id header names a DIFFERENT attacker tenant
    // to PROVE the client header is ignored: the dispute must land under the BODY's
    // tenant, never the header's.
    private static final String BODY_TENANT = "victimTenant";
    private static final String ATTACKER_HEADER_TENANT = "attackerTenant";

    // The signed body carries tenant_id — the server-authoritative tenant source.
    // The X-Tenant-Id header (set on the requests below) must be IGNORED.
    private static final String DISPUTE_BODY = """
            {
              "event_type": "dispute.opened",
              "tenant_id": "victimTenant",
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

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DOCKER_AVAILABLE,
                "Docker unavailable — dispute webhook red-team self-skips (Testcontainers required)");
    }

    @Test
    @DisplayName("an UNSIGNED dispute webhook is rejected (401), not processed")
    void unsignedDisputeWebhook_isRejected() throws Exception {
        long before = disputeCount(EXTERNAL_ID);

        mockMvc.perform(post("/internal/webhooks/disputes")
                        .header("X-Tenant-Id", "victimTenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DISPUTE_BODY))
                .andExpect(result ->
                        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                                .as("an unsigned/forged dispute webhook must be rejected, not accepted")
                                .isIn(401, 403));

        // Fail-closed: no dispute row, hence no chargeback reserve, was created.
        org.assertj.core.api.Assertions.assertThat(disputeCount(EXTERNAL_ID) - before)
                .as("an unsigned dispute webhook must create NO dispute (no chargeback reserve)")
                .isZero();
    }

    @Test
    @DisplayName("a REPLAYED identical SIGNED dispute event books no second chargeback reserve, "
            + "and the dispute lands under the BODY's tenant (client X-Tenant-Id ignored)")
    void replayedDisputeWebhook_doesNotDoubleBookChargeback() throws Exception {
        String sig = hmacSha512Hex(DISPUTE_BODY);
        long before = disputeCount(EXTERNAL_ID);

        // First SIGNED delivery — accepted, creates exactly one dispute + one
        // reserve, and returns {"status":"created"}. The X-Tenant-Id header names
        // a DIFFERENT (attacker) tenant than the signed body's tenant_id; if the
        // server honoured the header the dispute would land under the attacker.
        mockMvc.perform(post("/internal/webhooks/disputes")
                        .header("X-Tenant-Id", ATTACKER_HEADER_TENANT)   // must be IGNORED
                        .header("x-webhook-signature", sig)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DISPUTE_BODY))
                .andExpect(result -> {
                    org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                            .as("a signed+valid dispute webhook must be accepted (200) and processed once")
                            .isEqualTo(200);
                    org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                            .as("the first signed delivery creates the dispute")
                            .contains("\"status\":\"created\"");
                });

        // Redelivery of the IDENTICAL signed event — deduped on
        // (tenant_id, external_dispute_id): NO second dispute / second reserve.
        // Header again names the attacker tenant; still ignored.
        mockMvc.perform(post("/internal/webhooks/disputes")
                        .header("X-Tenant-Id", ATTACKER_HEADER_TENANT)   // must be IGNORED
                        .header("x-webhook-signature", sig)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DISPUTE_BODY))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(body)
                            .as("replayed dispute must not be re-created (no double chargeback reserve)")
                            .doesNotContain("\"status\":\"created\"");
                });

        // Source-of-truth exactly-once: exactly ONE dispute row for this external
        // id was persisted across the two deliveries → the chargeback reserve was
        // posted exactly once (mirror of the HyperSwitch outbox-row-count guard).
        org.assertj.core.api.Assertions.assertThat(disputeCount(EXTERNAL_ID) - before)
                .as("a replayed signed dispute event must persist exactly one dispute (reserve posts once)")
                .isEqualTo(1L);

        // SERVER-AUTHORITATIVE TENANT (SEC-BATCH-1 / L-048): the dispute must be
        // attributed to the HMAC-verified BODY tenant, NOT the X-Tenant-Id header.
        // If the header were trusted, the row would carry the attacker tenant and
        // these assertions would fail — proving the header is ignored.
        org.assertj.core.api.Assertions.assertThat(disputeTenant(EXTERNAL_ID))
                .as("the dispute must be persisted under the signed BODY tenant, "
                        + "not the client X-Tenant-Id header")
                .isEqualTo(BODY_TENANT);
        org.assertj.core.api.Assertions.assertThat(disputeCountForTenant(EXTERNAL_ID, ATTACKER_HEADER_TENANT))
                .as("no dispute may be created under the attacker's X-Tenant-Id header tenant")
                .isZero();
    }

    private long disputeCount(String externalDisputeId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM disputes WHERE external_dispute_id = ?",
                Long.class, externalDisputeId);
        return n == null ? 0L : n;
    }

    /** The tenant the dispute for this external id was persisted under (server-authoritative). */
    private String disputeTenant(String externalDisputeId) {
        return jdbc.queryForObject(
                "SELECT tenant_id FROM disputes WHERE external_dispute_id = ?",
                String.class, externalDisputeId);
    }

    private long disputeCountForTenant(String externalDisputeId, String tenantId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM disputes WHERE external_dispute_id = ? AND tenant_id = ?",
                Long.class, externalDisputeId, tenantId);
        return n == null ? 0L : n;
    }

    private static String hmacSha512Hex(String payload) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGO);
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
        byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}
