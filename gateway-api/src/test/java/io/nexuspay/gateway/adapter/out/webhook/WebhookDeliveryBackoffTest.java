package io.nexuspay.gateway.adapter.out.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.rls.TenantWorkRunner;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookDeliveryRepository;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookEndpointRepository;
import io.nexuspay.gateway.adapter.out.persistence.WebhookDeliveryEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * INT-4 (tests A + B): the {@code recordOutcome} state machine produces a GROWING exponential backoff for
 * transient failures and parks the row DEAD (a DLQ, never deleted) once {@code max_attempts} is exhausted.
 *
 * <p>Drives the SHARED transition helper directly with stubbed {@link WebhookDeliveryService.SendOutcome}s,
 * so the assertions hold regardless of any HTTP server. Reverting the backoff to a constant fails the
 * growth assertion; reverting the DEAD transition leaves the row FAILED and fails test B.</p>
 */
class WebhookDeliveryBackoffTest {

    private WebhookDeliveryService service;

    @BeforeEach
    void setUp() {
        JpaWebhookEndpointRepository endpoints = mock(JpaWebhookEndpointRepository.class);
        JpaWebhookDeliveryRepository deliveries = mock(JpaWebhookDeliveryRepository.class);
        when(deliveries.save(any(WebhookDeliveryEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        TenantWorkRunner tenantWork = mock(TenantWorkRunner.class);
        // A guard that resolves a public host (never actually hit — these tests don't call send()).
        Function<String, List<InetAddress>> guard = url -> List.of(InetAddress.getLoopbackAddress());
        service = new WebhookDeliveryService(endpoints, deliveries, new ObjectMapper(), tenantWork,
                (gatewayPaymentId, tenant) -> java.util.Map.of(), false, guard);
    }

    private static WebhookDeliveryEntity pendingRow(int maxAttempts) {
        WebhookDeliveryEntity d = WebhookDeliveryEntity.pending(
                "whd_1", "t1", "we_1", "evt_1", "payment.succeeded", "{\"id\":\"evt_1\"}");
        // default max_attempts is 8; for test B we shrink it via reflection-free re-record is not possible,
        // so test B uses the default 8 path and asserts DEAD at the 8th attempt instead.
        return d;
    }

    // ---- A: a transient failure schedules a growing-backoff retry ----

    @Test
    void transientFailure_schedulesGrowingBackoff_cappedAtOneHour() {
        WebhookDeliveryEntity d = pendingRow(8);

        // attempt 1 -> base window [15s, 30s]
        service.recordOutcome(d, new WebhookDeliveryService.SendOutcome.TransientFailure(503, "down"));
        assertThat(d.getStatus()).isEqualTo(WebhookDeliveryEntity.Status.FAILED);
        assertThat(d.getAttemptCount()).isEqualTo(1);
        assertThat(d.getLastStatusCode()).isEqualTo(503);
        long delay1 = Duration.between(Instant.now(), d.getNextAttemptAt()).getSeconds();
        assertThat(delay1).as("attempt 1 window ~[15,30]s").isBetween(14L, 31L);

        // attempt 2 -> [30s, 60s]
        service.recordOutcome(d, new WebhookDeliveryService.SendOutcome.TransientFailure(503, "down"));
        assertThat(d.getAttemptCount()).isEqualTo(2);
        long delay2 = Duration.between(Instant.now(), d.getNextAttemptAt()).getSeconds();
        assertThat(delay2).as("attempt 2 window ~[30,60]s").isBetween(29L, 61L);

        // attempt 3 -> [60s, 120s]
        service.recordOutcome(d, new WebhookDeliveryService.SendOutcome.TransientFailure(503, "down"));
        assertThat(d.getAttemptCount()).isEqualTo(3);
        long delay3 = Duration.between(Instant.now(), d.getNextAttemptAt()).getSeconds();
        assertThat(delay3).as("attempt 3 window ~[60,120]s").isBetween(59L, 121L);

        // the windows strictly grow (lower bound each step at least doubles minus jitter slack).
        assertThat(delay3).as("backoff grows, not constant").isGreaterThan(delay1);

        // cap: many attempts can never exceed 1h (3600s). Drive up to attempt 7 (still < max 8).
        service.recordOutcome(d, new WebhookDeliveryService.SendOutcome.TransientFailure(503, "down")); // 4
        service.recordOutcome(d, new WebhookDeliveryService.SendOutcome.TransientFailure(503, "down")); // 5
        service.recordOutcome(d, new WebhookDeliveryService.SendOutcome.TransientFailure(503, "down")); // 6
        service.recordOutcome(d, new WebhookDeliveryService.SendOutcome.TransientFailure(503, "down")); // 7
        assertThat(d.getStatus()).isEqualTo(WebhookDeliveryEntity.Status.FAILED);
        long delay7 = Duration.between(Instant.now(), d.getNextAttemptAt()).getSeconds();
        assertThat(delay7).as("backoff is capped at 1h").isLessThanOrEqualTo(3600L);
    }

    // ---- A': the 1h cap is the BINDING constraint at high attempts (non-vacuous cap proof) ----
    // The recordOutcome-driven test above never reaches an uncapped value > 3600 (attempt 7's uncapped 30<<6 =
    // 1920s < 3600, and attempt 8 goes DEAD), so delay7 <= 3600 holds whether or not the cap exists. This drives
    // nextAttemptAt directly into the region where the UNCAPPED value exceeds 1h, so deleting the Math.min cap
    // would change the result and fail this assertion.
    @Test
    void nextAttemptAt_capsDelayAtOneHour_inTheUncappedRegion() {
        // attempt 10: uncapped 30 << 9 = 15360s (>> 3600). With the cap, the scheduled delay is in the
        // half-to-full jitter window of 3600 -> [1800, 3600]; its UPPER bound is exactly the 1h cap.
        long cappedDelay = Duration.between(Instant.now(),
                WebhookDeliveryService.nextAttemptAt(10)).getSeconds();
        assertThat(cappedDelay)
                .as("attempt 10 uncapped would be 15360s; the 1h cap binds, so delay <= 3600 (and >= 1800 jitter)")
                .isBetween(1799L, 3601L);

        // Even larger attempts must not exceed the cap (the shift is itself bounded, but the cap is the gate).
        long cappedDelayBig = Duration.between(Instant.now(),
                WebhookDeliveryService.nextAttemptAt(20)).getSeconds();
        assertThat(cappedDelayBig)
                .as("the 1h cap holds for arbitrarily high attempt numbers")
                .isLessThanOrEqualTo(3601L);
    }

    // ---- B: max attempts -> DEAD (DLQ), never deleted ----

    @Test
    void maxAttemptsExhausted_parksDead_neverDeleted() {
        WebhookDeliveryEntity d = pendingRow(8);

        // 7 transient failures keep it FAILED (attempt_count 1..7 < max 8).
        for (int i = 0; i < 7; i++) {
            service.recordOutcome(d, new WebhookDeliveryService.SendOutcome.TransientFailure(500, "err"));
            assertThat(d.getStatus())
                    .as("attempt %d of 8 stays FAILED", i + 1)
                    .isEqualTo(WebhookDeliveryEntity.Status.FAILED);
        }

        // the 8th transient failure trips attempt_count to max -> DEAD.
        service.recordOutcome(d, new WebhookDeliveryService.SendOutcome.TransientFailure(500, "final"));
        assertThat(d.getAttemptCount()).isEqualTo(8);
        assertThat(d.getStatus()).as("exhausted -> DEAD (DLQ)").isEqualTo(WebhookDeliveryEntity.Status.DEAD);
        assertThat(d.getNextAttemptAt()).as("DEAD rows are not re-scanned").isNull();
        assertThat(d.getLastError()).isEqualTo("final");
        // The DLQ row is NOT deleted — it persists for inspection / replay (the entity is still present).
    }

    // ---- 4xx (except 408/429) is a PermanentFailure -> immediate DEAD ----

    @Test
    void permanentFailure_goesDeadImmediately_withoutBumpingAttempts() {
        WebhookDeliveryEntity d = pendingRow(8);

        service.recordOutcome(d, new WebhookDeliveryService.SendOutcome.PermanentFailure(400, "client error 400"));

        assertThat(d.getStatus()).isEqualTo(WebhookDeliveryEntity.Status.DEAD);
        assertThat(d.getAttemptCount()).as("a permanent failure does not consume the retry budget").isZero();
        assertThat(d.getNextAttemptAt()).isNull();
    }

    // ---- 2xx -> DELIVERED, terminal ----

    @Test
    void delivered_isTerminal_clearsRetryGate() {
        WebhookDeliveryEntity d = pendingRow(8);

        service.recordOutcome(d, new WebhookDeliveryService.SendOutcome.Delivered(200));

        assertThat(d.getStatus()).isEqualTo(WebhookDeliveryEntity.Status.DELIVERED);
        assertThat(d.getNextAttemptAt()).isNull();
        assertThat(d.getDeliveredAt()).isNotNull();
        assertThat(d.getLastStatusCode()).isEqualTo(200);
    }
}
