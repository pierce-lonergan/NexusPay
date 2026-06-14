package io.nexuspay.gateway.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-014: {@link PaymentSession} is the lazy-expiration state machine that guards every checkout
 * read (TokenizationService / PaymentSessionService). With no background sweeper, the correctness
 * of isExpired()/isOpen()/markComplete/markExpired IS the security boundary for sessions.
 */
class PaymentSessionTest {

    private static PaymentSession session(String status, Instant expiresAt) {
        Instant created = Instant.now().minus(Duration.ofMinutes(5));
        return new PaymentSession(
                "ps_1", "t1", null, "secret",
                5000L, "USD", status, "cus_1",
                List.of("card"), "https://ok", "https://cancel",
                Map.of(), Map.of(),
                0, expiresAt, created, created);
    }

    // ---- isExpired ----

    @Test
    void isExpired_trueWhenStatusExpired_regardlessOfFutureExpiry() {
        // Status wins even though expiresAt is far in the future.
        PaymentSession s = session(PaymentSession.STATUS_EXPIRED, Instant.now().plus(Duration.ofHours(1)));
        assertThat(s.isExpired()).isTrue();
    }

    @Test
    void isExpired_trueWhenExpiresAtInPast_evenIfStatusOpen() {
        PaymentSession s = session(PaymentSession.STATUS_OPEN, Instant.now().minus(Duration.ofSeconds(1)));
        assertThat(s.isExpired()).isTrue();
    }

    @Test
    void isExpired_falseWhenOpenAndExpiryInFuture() {
        PaymentSession s = session(PaymentSession.STATUS_OPEN, Instant.now().plus(Duration.ofSeconds(30)));
        assertThat(s.isExpired()).isFalse();
    }

    @Test
    void isExpired_boundary_pastVsFutureByOneSecond() {
        // isExpired() uses Instant.now().isAfter(expiresAt). One second in the past is expired;
        // one second in the future is not. (now == expiresAt is racy by a few nanos, so we test +/- 1s.)
        assertThat(session(PaymentSession.STATUS_OPEN, Instant.now().minus(Duration.ofSeconds(1))).isExpired())
                .as("expiry one second ago is expired").isTrue();
        assertThat(session(PaymentSession.STATUS_OPEN, Instant.now().plus(Duration.ofSeconds(1))).isExpired())
                .as("expiry one second from now is not expired").isFalse();
    }

    // ---- isOpen ----

    @Test
    void isOpen_trueOnlyWhenOpenAndNotExpired() {
        PaymentSession s = session(PaymentSession.STATUS_OPEN, Instant.now().plus(Duration.ofMinutes(10)));
        assertThat(s.isOpen()).isTrue();
    }

    @Test
    void isOpen_falseWhenOpenButPastExpiry() {
        PaymentSession s = session(PaymentSession.STATUS_OPEN, Instant.now().minus(Duration.ofSeconds(1)));
        assertThat(s.isOpen()).isFalse();
    }

    @Test
    void isOpen_falseWhenComplete() {
        PaymentSession s = session(PaymentSession.STATUS_COMPLETE, Instant.now().plus(Duration.ofMinutes(10)));
        assertThat(s.isOpen()).isFalse();
    }

    // ---- markComplete ----

    @Test
    void markComplete_setsStatusAndIntentAndAdvancesUpdatedAt() {
        PaymentSession s = session(PaymentSession.STATUS_OPEN, Instant.now().plus(Duration.ofMinutes(10)));
        Instant before = s.getUpdatedAt();

        s.markComplete("pi_42");

        assertThat(s.getStatus()).isEqualTo(PaymentSession.STATUS_COMPLETE);
        assertThat(s.getPaymentIntentId()).isEqualTo("pi_42");
        assertThat(s.getUpdatedAt()).isAfterOrEqualTo(before);
        assertThat(s.isOpen()).isFalse();
    }

    // ---- markExpired ----

    @Test
    void markExpired_setsStatusExpiredAdvancesUpdatedAtAndClosesSession() {
        PaymentSession s = session(PaymentSession.STATUS_OPEN, Instant.now().plus(Duration.ofMinutes(10)));
        Instant before = s.getUpdatedAt();

        s.markExpired();

        assertThat(s.getStatus()).isEqualTo(PaymentSession.STATUS_EXPIRED);
        assertThat(s.getUpdatedAt()).isAfterOrEqualTo(before);
        assertThat(s.isExpired()).isTrue();
        assertThat(s.isOpen()).isFalse();
    }

    // ---- incrementTokenizeAttempts ----

    @Test
    void incrementTokenizeAttempts_returnsMonotonicCountAndBumpsUpdatedAt() {
        PaymentSession s = session(PaymentSession.STATUS_OPEN, Instant.now().plus(Duration.ofMinutes(10)));
        Instant before = s.getUpdatedAt();

        assertThat(s.incrementTokenizeAttempts()).isEqualTo(1);
        assertThat(s.incrementTokenizeAttempts()).isEqualTo(2);
        assertThat(s.incrementTokenizeAttempts()).isEqualTo(3);
        assertThat(s.getTokenizeAttempts()).isEqualTo(3);
        assertThat(s.getUpdatedAt()).isAfterOrEqualTo(before);
    }
}
