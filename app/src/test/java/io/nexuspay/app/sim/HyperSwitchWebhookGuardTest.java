package io.nexuspay.app.sim;

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
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IN-GATE guard test (UNTAGGED) — part of the simulation / red-team environment
 * (see {@code docs/simulation/README.md}).
 *
 * <p>Asserts the HyperSwitch inbound-webhook guards that ALREADY HOLD on current
 * main (the AUDIT-refuted "money-double-move" items), so it PASSES on main and is
 * safe in the default {@code ./gradlew test} gate:</p>
 * <ol>
 *   <li><strong>Signature rejection:</strong> when a webhook secret IS configured
 *       (the {@code test} profile sets {@code nexuspay.hyperswitch.webhook-secret}),
 *       an UNSIGNED webhook is rejected with 401 (constant-time HMAC-SHA512, L-007).</li>
 *   <li><strong>Duplicate dedup → no second outbox row:</strong> redelivering the
 *       SAME {@code event_id} (Valkey SET-NX, backed by the
 *       {@code inbound_webhooks.event_id} UNIQUE constraint) does NOT write a
 *       second {@code event_outbox} row — no double money-move.</li>
 * </ol>
 *
 * <p>These two guards (unsigned→401, duplicate→no second outbox row) are the
 * replay/auth backstops that DO hold on main. The B-015 pre-commit-dedup-vs-rollback
 * ORDERING bug is NOT covered by a red-team test: it is a fault-injection scenario
 * (force the @Transactional commit to fail AFTER the Valkey SET-NX) that cannot be
 * driven black-box over MockMvc — the would-be {@code HyperSwitchWebhookOrderingRedteamTest}
 * was removed because, as written, it sent an unsigned webhook (→401 before dedup) and
 * narrated a synchronous rollback this controller does not perform. See
 * docs/simulation/README.md (REMOVED) and AUDITS.md B-015.</p>
 *
 * <p>Self-skips without Docker via {@link IntegrationTestBase}.</p>
 */
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@DisplayName("HyperSwitch webhook guards hold on main (in-gate)")
class HyperSwitchWebhookGuardTest extends IntegrationTestBase {

    // Matches application-test.yml: nexuspay.hyperswitch.webhook-secret
    private static final String WEBHOOK_SECRET = "test_webhook_secret";
    private static final String HMAC_ALGO = "HmacSHA512";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DOCKER_AVAILABLE,
                "Docker unavailable — HyperSwitch guard test self-skips (Testcontainers required)");
    }

    @Test
    @DisplayName("an UNSIGNED webhook is rejected with 401 (secret is configured)")
    void unsignedWebhook_isRejected401() throws Exception {
        String body = webhookBody("evt_guard_unsigned_" + UUID.randomUUID(), "pay_guard_1");
        mockMvc.perform(post("/internal/webhooks/hyperswitch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a duplicate (same event_id) signed webhook writes no second outbox row")
    void duplicateSignedWebhook_writesNoSecondOutboxRow() throws Exception {
        String eventId = "evt_guard_dupe_" + UUID.randomUUID();
        String body = webhookBody(eventId, "pay_guard_dupe");
        String sig = hmacSha512Hex(body);

        long before = outboxRowCount();

        // First delivery — accepted (200), writes ONE outbox row.
        mockMvc.perform(post("/internal/webhooks/hyperswitch")
                        .header("x-webhook-signature", sig)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Redelivery of the IDENTICAL event — deduped (200), writes NO second row.
        mockMvc.perform(post("/internal/webhooks/hyperswitch")
                        .header("x-webhook-signature", sig)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        long after = outboxRowCount();
        org.assertj.core.api.Assertions.assertThat(after - before)
                .as("a duplicate event_id must add exactly one outbox row, not two (no double money-move)")
                .isEqualTo(1L);
    }

    private long outboxRowCount() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox", Long.class);
        return n == null ? 0L : n;
    }

    private static String webhookBody(String eventId, String paymentId) {
        return """
                {
                  "event_id": "%s",
                  "event_type": "payment_succeeded",
                  "content": { "object": { "payment_id": "%s", "amount": 5000, "currency": "USD" } }
                }
                """.formatted(eventId, paymentId);
    }

    private static String hmacSha512Hex(String payload) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGO);
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
        byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}
