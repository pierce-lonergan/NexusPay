package io.nexuspay.payment.adapter.in.webhook;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.payment.adapter.out.outbox.OutboxEvent;
import io.nexuspay.payment.adapter.out.outbox.OutboxEventRepository;
import io.nexuspay.payment.application.screening.ScreeningMode;
import io.nexuspay.payment.application.screening.ScreeningOriginService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GAP-015 atomicity: the reprocess endpoint writes the outbox row FIRST, then flips the inbound status —
 * both inside ONE {@code @Transactional}. This Docker-free test proves the CODE-LEVEL guarantee that
 * underpins the single-tx atomicity: when the outbox insert throws, the controller NEVER reaches
 * {@code markReprocessed()} / the status-flip save, so the inbound row is left FAILED and the propagated
 * exception rolls the (real) transaction back — no partial outbox row is committed and the row stays
 * re-drivable.
 *
 * <p>The end-to-end rollback against real Postgres is exercised by the app-module integration suite; here
 * we pin that the controller does not "swallow + mark processed" on an outbox failure (which would strand
 * the row PROCESSED with no outbox event — the exact defect the single-tx design prevents).</p>
 */
class WebhookReprocessAtomicityTest {

    private InboundWebhookRepository webhookRepository;
    private OutboxEventRepository outboxRepository;
    private ScreeningOriginService screeningOrigins;
    private WebhookReprocessController controller;

    private static final String RAW_PAYLOAD = """
            {
              "event_id": "evt_orig",
              "event_type": "payment_succeeded",
              "content": { "object": { "payment_id": "pay_123" } }
            }
            """;

    @BeforeEach
    void setUp() {
        webhookRepository = mock(InboundWebhookRepository.class);
        outboxRepository = mock(OutboxEventRepository.class);
        screeningOrigins = mock(ScreeningOriginService.class);
        WebhookOutboxWriter writer = new WebhookOutboxWriter(
                outboxRepository, new ObjectMapper(), screeningOrigins, new SimpleMeterRegistry());
        controller = new WebhookReprocessController(webhookRepository, writer);
    }

    @Test
    void outboxInsertThrows_inboundRowStaysFailed_andStatusFlipNeverPersisted() {
        InboundWebhook w = new InboundWebhook("wh_1", "evt_orig", "payment_succeeded", RAW_PAYLOAD);
        w.markFailed();
        when(webhookRepository.findById("wh_1")).thenReturn(Optional.of(w));
        when(screeningOrigins.find("pay_123"))
                .thenReturn(Optional.of(new ScreeningOriginService.Origin("tenant-A", ScreeningMode.INTERACTIVE)));
        // The outbox insert fails (e.g. a DB constraint / connection error).
        doThrow(new RuntimeException("simulated outbox insert failure"))
                .when(outboxRepository).save(any(OutboxEvent.class));

        // The controller lets the exception propagate so the surrounding @Transactional rolls back.
        assertThatThrownBy(() -> controller.reprocess("wh_1"))
                .isInstanceOf(RuntimeException.class);

        // The status flip was NEVER reached — the row is still FAILED (re-drivable), reprocessed_at unset.
        assertThat(w.getStatus()).isEqualTo(InboundWebhook.STATUS_FAILED);
        assertThat(w.getReprocessedAt()).isNull();
        // Crucially, the inbound row was NOT saved as PROCESSED (no swallow-and-mark).
        verify(webhookRepository, never()).save(any(InboundWebhook.class));
    }
}
