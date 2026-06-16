package io.nexuspay.app;

import io.nexuspay.app.config.TestSecurityConfig;
import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookDeliveryRepository;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookEndpointRepository;
import io.nexuspay.gateway.adapter.out.persistence.WebhookDeliveryEntity;
import io.nexuspay.gateway.adapter.out.persistence.WebhookEndpointEntity;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * INT-4 (tests E + G), full Spring boot against real Postgres (Testcontainers):
 *
 * <ul>
 *   <li><b>E:</b> {@code GET /v1/webhook-deliveries} is tenant-scoped and its JSON carries NO {@code secret}
 *       or {@code canonical_body}; {@code POST /v1/webhook-deliveries/{id}/replay} is tenant-scoped (a
 *       foreign id -> 404, no existence oracle) and re-arms a DELIVERED row to FAILED + due.</li>
 *   <li><b>G:</b> two {@code saveAndFlush} of the same {@code (endpoint_id, event_id)} -> the SECOND throws
 *       {@link DataIntegrityViolationException} (the {@code uq_webhook_deliveries_endpoint_event} unique
 *       index), proving the L-041 idempotency backstop against a REAL DB — a mock can't prove this.</li>
 * </ul>
 */
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
// Disable the background retrier so a seeded/replayed FAILED row is not concurrently re-driven (which would
// make a real outbound POST to example.com) — these tests assert ledger state, not the live retry loop (that
// is covered deterministically by WebhookDeliveryRetrierSecurePathTest against a loopback receiver).
@org.springframework.test.context.TestPropertySource(properties = "nexuspay.webhook.retry.enabled=false")
@DisplayName("INT-4: webhook delivery ledger — list/replay tenant-scoping + idempotent recording")
class WebhookDeliveryReliabilityIT extends IntegrationTestBase {

    private static final String TENANT = "default";
    private static final String OTHER_TENANT = "other-tenant";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JpaWebhookEndpointRepository endpointRepository;

    @Autowired
    private JpaWebhookDeliveryRepository deliveryRepository;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DOCKER_AVAILABLE,
                "Docker unavailable — INT-4 reliability IT self-skips (Testcontainers required)");
    }

    private WebhookEndpointEntity seedEndpoint(String tenant) {
        // L-055: example.com resolves; we seed the entity directly (no @SafeWebhookUrl gate on a repo write).
        var ep = new WebhookEndpointEntity(PrefixedId.webhookEndpoint(),
                "https://example.com/hooks", "d", "whsec_seed_secret",
                List.of("payment.succeeded"), tenant);
        return endpointRepository.saveAndFlush(ep);
    }

    private WebhookDeliveryEntity seedDelivery(String tenant, String endpointId, String eventId,
                                               WebhookDeliveryEntity.Status status) {
        var d = WebhookDeliveryEntity.pending(PrefixedId.webhookDelivery(), tenant, endpointId, eventId,
                "payment.succeeded", "{\"id\":\"" + eventId + "\",\"type\":\"payment.succeeded\"}");
        switch (status) {
            case DELIVERED -> d.markDelivered(200);
            case FAILED -> {
                d.incrementAttempt();
                // schedule the next attempt comfortably in the future so even an enabled retrier wouldn't be due.
                d.markTransientFailure(503, "seed", Instant.now().plusSeconds(3600));
            }
            case DEAD -> d.markDead(400, "seed");
            case PENDING -> { /* as recorded */ }
        }
        return deliveryRepository.saveAndFlush(d);
    }

    // ---- E: list is tenant-scoped and leaks neither secret nor canonical body ----

    @Test
    @DisplayName("list is tenant-scoped and exposes no secret / canonical body")
    void listDeliveries_tenantScoped_noSecretOrBody() throws Exception {
        var epA = seedEndpoint(TENANT);
        var epB = seedEndpoint(OTHER_TENANT);
        var mine = seedDelivery(TENANT, epA.getId(), "evt_mine", WebhookDeliveryEntity.Status.FAILED);
        seedDelivery(OTHER_TENANT, epB.getId(), "evt_other", WebhookDeliveryEntity.Status.FAILED);

        mockMvc.perform(get("/v1/webhook-deliveries")
                        .with(authentication(TestSecurityConfig.authFor(TENANT, "admin"))))
                .andExpect(status().isOk())
                // sees my row...
                .andExpect(jsonPath("$.content[?(@.id=='" + mine.getId() + "')]").exists())
                // ...but NOT the other tenant's
                .andExpect(jsonPath("$.content[?(@.event_id=='evt_other')]").doesNotExist())
                // and NEVER a secret or canonical body field on any row
                .andExpect(jsonPath("$.content[*].secret").doesNotExist())
                .andExpect(jsonPath("$.content[*].canonical_body").doesNotExist());
    }

    // ---- E: replay is tenant-scoped (foreign -> 404) and re-arms a DELIVERED row ----

    @Test
    @DisplayName("replay of a foreign-tenant delivery is 404 (no oracle)")
    void replay_foreignTenant_is404() throws Exception {
        var epB = seedEndpoint(OTHER_TENANT);
        var foreign = seedDelivery(OTHER_TENANT, epB.getId(), "evt_foreign", WebhookDeliveryEntity.Status.FAILED);

        mockMvc.perform(post("/v1/webhook-deliveries/" + foreign.getId() + "/replay")
                        .with(authentication(TestSecurityConfig.authFor(TENANT, "admin"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("replay re-arms a DELIVERED row to FAILED + immediately due (202)")
    void replay_reArmsDeliveredRow() throws Exception {
        var ep = seedEndpoint(TENANT);
        var delivered = seedDelivery(TENANT, ep.getId(), "evt_replay", WebhookDeliveryEntity.Status.DELIVERED);
        assertThat(delivered.getStatus()).isEqualTo(WebhookDeliveryEntity.Status.DELIVERED);

        mockMvc.perform(post("/v1/webhook-deliveries/" + delivered.getId() + "/replay")
                        .with(authentication(TestSecurityConfig.authFor(TENANT, "admin"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.next_attempt_at").exists());

        var reloaded = deliveryRepository.findById(delivered.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(WebhookDeliveryEntity.Status.FAILED);
        assertThat(reloaded.getNextAttemptAt()).isNotNull();
        assertThat(reloaded.getNextAttemptAt()).isBeforeOrEqualTo(Instant.now().plusSeconds(5));
    }

    // ---- G: idempotent double-record against a REAL DB (L-041) ----

    @Test
    @DisplayName("a duplicate (endpoint_id, event_id) saveAndFlush is rejected by the unique index")
    void duplicateRecording_isRejectedByUniqueIndex() {
        var ep = seedEndpoint(TENANT);
        seedDelivery(TENANT, ep.getId(), "evt_dup", WebhookDeliveryEntity.Status.PENDING);

        var dup = WebhookDeliveryEntity.pending(PrefixedId.webhookDelivery(), TENANT, ep.getId(),
                "evt_dup", "payment.succeeded", "{\"id\":\"evt_dup\"}");

        assertThatThrownBy(() -> deliveryRepository.saveAndFlush(dup))
                .as("the uq_webhook_deliveries_endpoint_event index rejects the duplicate at flush (L-041)")
                .isInstanceOf(DataIntegrityViolationException.class);

        // exactly one row persists for the (endpoint, event) pair.
        assertThat(deliveryRepository.findByEndpointIdAndEventId(ep.getId(), "evt_dup")).isPresent();
    }
}
