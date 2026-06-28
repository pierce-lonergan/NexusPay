package io.nexuspay.payment.adapter.in.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.payment.adapter.out.outbox.OutboxEventRepository;
import io.nexuspay.payment.application.screening.ScreeningMode;
import io.nexuspay.payment.application.screening.ScreeningOriginService;
import io.nexuspay.payment.application.service.projection.PaymentProjectionService;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.RefundResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GAP-076 (critique v3 F1): ASYNC-LIVE settlement — the HyperSwitch webhook is the ONLY place a LIVE
 * payment/refund transitions without a gateway call, so the read-model status advance for those must be
 * driven here.
 *
 * <ul>
 *   <li>a {@code payment_succeeded} webhook maps to + calls
 *       {@code recordStatusUpdate(paymentId, tenant, "succeeded", livemode)} (the webhook->service WIRING +
 *       event-type mapping + tenant/livemode resolution);</li>
 *   <li>a {@code refund_succeeded} maps to the refund projection advance;</li>
 *   <li>a projection throw in the webhook path is SWALLOWED and does NOT fail the 200 OK / outbox write
 *       (the cardinal rule).</li>
 * </ul>
 *
 * <p>The BEHAVIORAL transition itself (a STORED processing row actually moving to succeeded through
 * {@code updateStatusIfExists}, precedence-guarded, with the forward-fill no-op) is proven separately:
 * against real Postgres in {@code app}'s {@code PaymentProjectionReadModelIntegrationTest}, and without
 * Docker in {@code PaymentProjectionServiceTest.recordStatusUpdate_advancesStoredRow_…}. Here we only pin
 * that the webhook RESOLVES the right args and CALLS the service — a complementary seam, not a tautology.</p>
 */
class HyperSwitchWebhookProjectionTest {

    private static final String WEBHOOK_SECRET = "unit_test_webhook_secret";
    private static final String HMAC_ALGO = "HmacSHA512";

    private InboundWebhookRepository webhookRepository;
    private OutboxEventRepository outboxRepository;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ScreeningOriginService screeningOrigins;
    private PaymentProjectionService projection;
    private HyperSwitchWebhookController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        webhookRepository = mock(InboundWebhookRepository.class);
        outboxRepository = mock(OutboxEventRepository.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        screeningOrigins = mock(ScreeningOriginService.class);
        projection = mock(PaymentProjectionService.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(Boolean.TRUE);
        when(screeningOrigins.find(anyString()))
                .thenReturn(Optional.of(new ScreeningOriginService.Origin("tenant-A", ScreeningMode.INTERACTIVE)));

        controller = new HyperSwitchWebhookController(
                webhookRepository, outboxRepository, redisTemplate, new ObjectMapper(),
                screeningOrigins, projection, new SimpleMeterRegistry(), WEBHOOK_SECRET);
    }

    private static String signed(String payload) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGO);
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void paymentSucceeded_advancesProjectionToSucceeded() throws Exception {
        String payload = """
                {
                  "event_id": "evt_1",
                  "event_type": "payment_succeeded",
                  "content": { "object": { "payment_id": "pay_live_1", "livemode": true } }
                }
                """;

        var response = controller.handleWebhook(payload, signed(payload));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(projection).recordStatusUpdate(
                eq("pay_live_1"), eq("tenant-A"), eq(PaymentResponse.STATUS_SUCCEEDED), eq(true));
    }

    @Test
    void refundSucceeded_advancesRefundProjection() throws Exception {
        String payload = """
                {
                  "event_id": "evt_2",
                  "event_type": "refund_succeeded",
                  "content": { "object": { "payment_id": "pay_live_2", "refund_id": "re_live_2", "livemode": true } }
                }
                """;

        var response = controller.handleWebhook(payload, signed(payload));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(projection).recordRefundStatusUpdate(
                eq("re_live_2"), eq("pay_live_2"), eq("tenant-A"), eq(RefundResponse.STATUS_SUCCEEDED), eq(true));
    }

    @Test
    void projectionThrows_isSwallowed_outboxStillWritten_and200() throws Exception {
        // The projection service is supposed to swallow internally, but even a stray throw here must not
        // break the 200 OK / outbox write (the webhook wraps the call defensively).
        doThrow(new RuntimeException("boom")).when(projection)
                .recordStatusUpdate(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());

        String payload = """
                {
                  "event_id": "evt_3",
                  "event_type": "payment_succeeded",
                  "content": { "object": { "payment_id": "pay_live_3", "livemode": true } }
                }
                """;

        var response = controller.handleWebhook(payload, signed(payload));

        assertThat(response.getStatusCode().value()).isEqualTo(200); // 200 unaffected
        verify(outboxRepository).save(any());                        // outbox write happened
    }
}
