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

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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

        // Empty webhook secret -> signature verification is skipped (dev path), so the test posts unsigned.
        controller = new HyperSwitchWebhookController(
                webhookRepository, outboxRepository, redisTemplate, new ObjectMapper(),
                screeningOrigins, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), "");
    }

    @Test
    void outboxEvent_isStampedWithOriginTenant_notDefault() {
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

        controller.handleWebhook(payload, null);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getTenantId())
                .as("outbox event must carry the TRUSTED origin tenant, not the legacy \"default\"")
                .isEqualTo("tenant-A");
        verify(screeningOrigins).find(paymentId);
    }

    @Test
    void outboxEvent_fallsBackToDefault_whenNoOriginExists() {
        String paymentId = "pay_unknown";
        when(screeningOrigins.find(paymentId)).thenReturn(Optional.empty());

        String payload = """
                {
                  "event_id": "evt_2",
                  "event_type": "payment_succeeded",
                  "content": { "object": { "payment_id": "pay_unknown" } }
                }
                """;

        controller.handleWebhook(payload, null);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getTenantId())
                .as("absent origin -> \"default\" (transition gap, not a leak)")
                .isEqualTo("default");
    }
}
