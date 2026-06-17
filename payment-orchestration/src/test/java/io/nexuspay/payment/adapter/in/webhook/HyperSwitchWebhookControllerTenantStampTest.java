package io.nexuspay.payment.adapter.in.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.payment.adapter.out.outbox.OutboxEvent;
import io.nexuspay.payment.adapter.out.outbox.OutboxEventRepository;
import io.nexuspay.payment.application.screening.ScreeningMode;
import io.nexuspay.payment.application.screening.ScreeningOriginService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SEC-09 (B-009) producer half: the PSP-inbound webhook path must stamp the TRUSTED origin tenant on the
 * outbox event (recalled from {@link ScreeningOriginService} by gateway payment id), NOT the legacy
 * {@code "default"} of the 4-arg {@link OutboxEvent} constructor. Without this the relay/consumer cannot
 * fan out to the right tenant.
 *
 * <p>Asserts the persisted {@link OutboxEvent#getTenantId()} equals the origin tenant. FAILS if the
 * producer reverts to the 4-arg ctor (which hardcodes {@code "default"}).</p>
 */
class HyperSwitchWebhookControllerTenantStampTest {

    // SEC-28: the gate is now UNCONDITIONALLY fail-closed, so this unit test configures a secret and
    // SIGNS each payload (the blank-secret skip the test previously relied on is gone).
    private static final String WEBHOOK_SECRET = "unit_test_webhook_secret";
    private static final String HMAC_ALGO = "HmacSHA512";

    private InboundWebhookRepository webhookRepository;
    private OutboxEventRepository outboxRepository;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ScreeningOriginService screeningOrigins;
    private HyperSwitchWebhookController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        webhookRepository = mock(InboundWebhookRepository.class);
        outboxRepository = mock(OutboxEventRepository.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        screeningOrigins = mock(ScreeningOriginService.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // setIfAbsent -> TRUE means "new" (not a duplicate) so processing proceeds to the outbox write.
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(Boolean.TRUE);

        // SEC-28: a NON-BLANK secret so the fail-closed gate verifies the (signed) payload instead of
        // rejecting it. Each test signs its body with WEBHOOK_SECRET via signed(...).
        controller = new HyperSwitchWebhookController(
                webhookRepository, outboxRepository, redisTemplate, new ObjectMapper(),
                screeningOrigins, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                WEBHOOK_SECRET);
    }

    private static String signed(String payload) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGO);
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
        byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    @Test
    void outboxEvent_isStampedWithOriginTenant_notDefault() throws Exception {
        String paymentId = "pay_123";
        when(screeningOrigins.find(paymentId))
                .thenReturn(Optional.of(new ScreeningOriginService.Origin("tenant-A", ScreeningMode.INTERACTIVE)));

        String payload = """
                {
                  "event_id": "evt_1",
                  "event_type": "payment_succeeded",
                  "content": { "object": { "payment_id": "pay_123" } }
                }
                """;

        controller.handleWebhook(payload, signed(payload));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getTenantId())
                .as("outbox event must carry the TRUSTED origin tenant, not the legacy \"default\"")
                .isEqualTo("tenant-A");
        verify(screeningOrigins).find(paymentId);
    }

    @Test
    void outboxEvent_fallsBackToDefault_whenNoOriginExists() throws Exception {
        String paymentId = "pay_unknown";
        when(screeningOrigins.find(paymentId)).thenReturn(Optional.empty());

        String payload = """
                {
                  "event_id": "evt_2",
                  "event_type": "payment_succeeded",
                  "content": { "object": { "payment_id": "pay_unknown" } }
                }
                """;

        controller.handleWebhook(payload, signed(payload));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getTenantId())
                .as("absent origin -> \"default\" (transition gap, not a leak)")
                .isEqualTo("default");
    }

    @Test
    void unsignedWebhook_withConfiguredSecret_is401_andWritesNoOutboxRow() {
        // SEC-28 regression: with a NON-BLANK secret configured, an UNSIGNED webhook (null signature)
        // must be rejected fail-closed (401) BEFORE any parse/dedup/outbox write. Previously the
        // blank-secret skip would have let an unsigned event through; here the gate rejects it.
        String payload = """
                {
                  "event_id": "evt_unsigned",
                  "event_type": "payment_succeeded",
                  "content": { "object": { "payment_id": "pay_unsigned" } }
                }
                """;

        var response = controller.handleWebhook(payload, null);

        assertThat(response.getStatusCode().value())
                .as("an unsigned webhook with a configured secret must be rejected 401 (fail-closed)")
                .isEqualTo(401);
        // No durable side effects: no outbox row, no dedup claim, no origin lookup.
        verify(outboxRepository, never()).save(any());
        verify(webhookRepository, never()).save(any());
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(screeningOrigins, never()).find(anyString());
    }

    @Test
    void blankSecret_rejectsEvenSignedLookingWebhook_failClosed() {
        // SEC-28: a blank secret is now fail-CLOSED (was fail-open). Construct a controller with a blank
        // secret and confirm even a signature-bearing request is rejected 401 with no outbox write.
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        OutboxEventRepository outbox = mock(OutboxEventRepository.class);
        InboundWebhookRepository hooks = mock(InboundWebhookRepository.class);
        ScreeningOriginService origins = mock(ScreeningOriginService.class);
        HyperSwitchWebhookController blankSecretController = new HyperSwitchWebhookController(
                hooks, outbox, redis, new ObjectMapper(), origins,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), "");

        var response = blankSecretController.handleWebhook("{\"event_id\":\"x\"}", "deadbeef");

        assertThat(response.getStatusCode().value())
                .as("a blank secret must fail closed (401), not skip verification (fail open)")
                .isEqualTo(401);
        verify(outbox, never()).save(any());
        verify(hooks, never()).save(any());
        verify(redis, never()).opsForValue();
    }
}
