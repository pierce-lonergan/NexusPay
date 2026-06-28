package io.nexuspay.payment.adapter.in.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nexuspay.payment.adapter.out.outbox.OutboxEvent;
import io.nexuspay.payment.adapter.out.outbox.OutboxEventRepository;
import io.nexuspay.payment.application.screening.ScreeningMode;
import io.nexuspay.payment.application.screening.ScreeningOriginService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SEC-15: the Valkey dedup claim ({@code SET NX} of {@code webhook:dedup:{eventId}}) is NOT enlisted in
 * the webhook handler's DB transaction. If the {@code @Transactional} handler rolls back AFTER the claim
 * (a DB/outbox failure or any unchecked exception before commit) the eventId would stay suppressed for the
 * 24h TTL and the legitimate, RETRYABLE webhook would never be redelivered. The fix RELEASES the claim
 * unless the tx COMMITS — via a {@link TransactionSynchronization#afterCompletion(int)} that deletes the
 * key on any non-COMMITTED status, with a no-active-synchronization try/catch fallback for direct calls.
 *
 * <p>These tests drive the controller directly (no Spring tx manager), so they exercise BOTH the
 * no-active-sync fallback (tests 5/6) AND a SIMULATED active synchronization whose {@code afterCompletion}
 * is invoked with the ROLLED_BACK / COMMITTED statuses (test 7).</p>
 *
 * <p><strong>Each test FAILS if the SEC-15 release is reverted</strong> — vulnerable code never deletes
 * the dedup key, so the rolled-back event stays suppressed and a redelivery would be (wrongly) 200-skipped
 * instead of processed.</p>
 */
@DisplayName("SEC-15 webhook dedup-key release on tx rollback")
class WebhookDedupRollbackReleaseTest {

    private static final String DEDUP_PREFIX = "webhook:dedup:";
    private static final String EVENT_ID = "evt_x";
    private static final String PAYMENT_ID = "pay_123";

    // SEC-28: the webhook gate is now UNCONDITIONALLY fail-closed (a blank secret -> 401 before any
    // parse/dedup/outbox work). These tests assert SEC-15 dedup-release logic, which lives AFTER the gate,
    // so they configure a non-blank secret and SIGN each payload (HmacSHA512 hex) — mirroring the sibling
    // HyperSwitchWebhookControllerTenantStampTest. The blank-secret "skip verification" dev path is gone.
    private static final String WEBHOOK_SECRET = "unit_test_webhook_secret";
    private static final String HMAC_ALGO = "HmacSHA512";

    private static final String PAYLOAD = """
            {
              "event_id": "evt_x",
              "event_type": "payment_succeeded",
              "content": { "object": { "payment_id": "pay_123" } }
            }
            """;

    /** Signs a payload with {@link #WEBHOOK_SECRET} exactly as {@code verifySignature} expects (hex HMAC). */
    private static String signed(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("failed to sign test payload", e);
        }
    }

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
        when(screeningOrigins.find(PAYMENT_ID))
                .thenReturn(Optional.of(new ScreeningOriginService.Origin("tenant-A", ScreeningMode.INTERACTIVE)));

        // SEC-28: a NON-BLANK secret so the fail-closed gate verifies the (signed) payload instead of
        // rejecting it 401. Each handleWebhook call below passes signed(PAYLOAD) so the post-gate SEC-15
        // dedup-release logic under test is actually reached.
        controller = new HyperSwitchWebhookController(
                webhookRepository, outboxRepository, redisTemplate, new ObjectMapper(),
                screeningOrigins,
                mock(io.nexuspay.payment.application.service.projection.PaymentProjectionService.class),
                new SimpleMeterRegistry(), WEBHOOK_SECRET);
    }

    @AfterEach
    void tearDown() {
        // Never leak a thread-bound synchronization between tests.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---- Test 5: a rollback (post-claim failure) releases the claim; the redelivery is then processed ----

    @Test
    @DisplayName("rollback releases the dedup key so the redelivery is processed (FAILS if release reverted)")
    void postClaimFailure_releasesDedupKey_andRedeliveryIsProcessed() {
        // First delivery: claim succeeds (TRUE), then the outbox write throws -> the post-claim body
        // throws -> the no-active-sync fallback must delete the dedup key.
        when(valueOps.setIfAbsent(eq(DEDUP_PREFIX + EVENT_ID), anyString(), any(Duration.class)))
                .thenReturn(Boolean.TRUE);
        when(outboxRepository.save(any(OutboxEvent.class)))
                .thenThrow(new RuntimeException("DB down — tx will roll back"));

        assertThatThrownBy(() -> controller.handleWebhook(PAYLOAD, signed(PAYLOAD)))
                .isInstanceOf(RuntimeException.class);

        // The claim was RELEASED (vulnerable code never calls delete -> this assertion fails on revert).
        verify(redisTemplate).delete(DEDUP_PREFIX + EVENT_ID);

        // Redelivery: the key is gone, so setIfAbsent returns TRUE again and the event is PROCESSED
        // (the outbox save now succeeds), not suppressed.
        org.mockito.Mockito.reset(outboxRepository);
        when(outboxRepository.save(any(OutboxEvent.class))).thenReturn(null);

        var response = controller.handleWebhook(PAYLOAD, signed(PAYLOAD));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(outboxRepository).save(any(OutboxEvent.class)); // re-processed, NOT 200-skipped
    }

    // ---- Test 6: a successfully-processed event stays deduped (invariant 3) ----

    @Test
    @DisplayName("successful processing keeps the dedup key; a true duplicate is 200-skipped (no re-save)")
    void successfulProcessing_keepsDedupKey_andDuplicateIsSkipped() {
        // First delivery: claim TRUE, processing succeeds.
        when(valueOps.setIfAbsent(eq(DEDUP_PREFIX + EVENT_ID), anyString(), any(Duration.class)))
                .thenReturn(Boolean.TRUE);
        when(outboxRepository.save(any(OutboxEvent.class))).thenReturn(null);

        var first = controller.handleWebhook(PAYLOAD, signed(PAYLOAD));
        assertThat(first.getStatusCode().value()).isEqualTo(200);

        // On the happy path the key is NOT released (no active tx here; the fallback only deletes on a
        // thrown exception, which did not happen). Invariant 3: a successfully-processed event stays deduped.
        verify(redisTemplate, never()).delete(anyString());

        // Second (duplicate) delivery: setIfAbsent now returns FALSE -> 200, NO second outbox save.
        when(valueOps.setIfAbsent(eq(DEDUP_PREFIX + EVENT_ID), anyString(), any(Duration.class)))
                .thenReturn(Boolean.FALSE);
        var dup = controller.handleWebhook(PAYLOAD, signed(PAYLOAD));
        assertThat(dup.getStatusCode().value()).isEqualTo(200);
        verify(outboxRepository, times(1)).save(any(OutboxEvent.class)); // still only the first save
    }

    // ---- Test 7 (tx-aware): the registered synchronization deletes on ROLLED_BACK, not on COMMITTED ----

    @Test
    @DisplayName("afterCompletion deletes on ROLLED_BACK and keeps on COMMITTED (FAILS if release reverted)")
    void afterCompletion_deletesOnRollback_keepsOnCommit() {
        // Simulate a Spring-managed tx so isSynchronizationActive() is true and the controller registers
        // a synchronization instead of taking the try/catch fallback.
        TransactionSynchronizationManager.initSynchronization();
        try {
            when(valueOps.setIfAbsent(eq(DEDUP_PREFIX + EVENT_ID), anyString(), any(Duration.class)))
                    .thenReturn(Boolean.TRUE);
            when(outboxRepository.save(any(OutboxEvent.class))).thenReturn(null);

            controller.handleWebhook(PAYLOAD, signed(PAYLOAD));

            List<TransactionSynchronization> syncs =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs)
                    .as("SEC-15 must register an afterCompletion synchronization after the claim")
                    .hasSize(1);
            TransactionSynchronization sync = syncs.get(0);

            // COMMITTED -> the key is RETAINED (the event is durable; stays deduped for the TTL).
            sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            verify(redisTemplate, never()).delete(anyString());

            // ROLLED_BACK -> the key is RELEASED so the redelivery is processed (FAILS on revert).
            sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(redisTemplate).delete(DEDUP_PREFIX + EVENT_ID);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("afterCompletion(STATUS_UNKNOWN) also releases (heuristic commit failure = redeliverable)")
    void afterCompletion_releasesOnUnknownStatus() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            when(valueOps.setIfAbsent(eq(DEDUP_PREFIX + EVENT_ID), anyString(), any(Duration.class)))
                    .thenReturn(Boolean.TRUE);
            when(outboxRepository.save(any(OutboxEvent.class))).thenReturn(null);

            controller.handleWebhook(PAYLOAD, signed(PAYLOAD));

            TransactionSynchronization sync =
                    TransactionSynchronizationManager.getSynchronizations().get(0);
            sync.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);
            verify(redisTemplate).delete(DEDUP_PREFIX + EVENT_ID);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
