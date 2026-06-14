package io.nexuspay.gateway.application.service;

import io.nexuspay.gateway.application.port.in.TokenizePaymentMethodUseCase.TokenizeCommand;
import io.nexuspay.gateway.application.port.out.PaymentSessionRepository;
import io.nexuspay.gateway.application.port.out.PaymentTokenRepository;
import io.nexuspay.gateway.domain.PaymentSession;
import io.nexuspay.gateway.domain.PaymentToken;
import io.nexuspay.gateway.domain.SessionExpiredException;
import io.nexuspay.gateway.domain.TokenizationRateLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-014: {@link TokenizationService} is the per-session anti-card-testing gate. It must (a) reject
 * unknown/expired/non-open sessions, (b) lock the session when the tokenize-attempt cap is exceeded
 * (the trip is {@code attempts > max}, NOT {@code >=}), and (c) mint a single-use token on the happy
 * path with server-side fields left null. A regression here lets attackers probe cards or wrongly
 * locks legitimate checkouts.
 */
class TokenizationServiceTest {

    private static final int MAX_ATTEMPTS = 10;
    private static final Duration SINGLE_USE_EXPIRY = Duration.ofMinutes(15);
    private static final Duration MULTI_USE_EXPIRY = Duration.ofDays(365);

    private PaymentSessionRepository sessionRepository;
    private PaymentTokenRepository tokenRepository;
    private TokenizationService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(PaymentSessionRepository.class);
        tokenRepository = mock(PaymentTokenRepository.class);
        service = new TokenizationService(sessionRepository, tokenRepository,
                MAX_ATTEMPTS, SINGLE_USE_EXPIRY, MULTI_USE_EXPIRY);
    }

    private static PaymentSession session(String status, Instant expiresAt) {
        Instant created = Instant.now().minus(Duration.ofMinutes(1));
        return new PaymentSession(
                "ps_1", "t1", null, "secret",
                5000L, "USD", status, "cus_1",
                List.of("card"), "https://ok", "https://cancel",
                Map.of(), Map.of(),
                0, expiresAt, created, created);
    }

    private static TokenizeCommand cardCommand() {
        return new TokenizeCommand(
                "ps_1", "t1", PaymentToken.TYPE_CARD,
                new byte[]{1, 2, 3}, "4242", "visa", 12, 2030);
    }

    @Test
    void unknownSession_throwsSessionExpired_andSavesNoToken() {
        when(sessionRepository.findById("ps_1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.tokenize(cardCommand()))
                .isInstanceOf(SessionExpiredException.class);

        verify(tokenRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(sessionRepository, never()).incrementTokenizeAttempts(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void expiredButStillOpenSession_locksSession_andThrows() {
        // expiresAt in the past, status still OPEN -> isExpired() true; service must persist the lock.
        PaymentSession expired = session(PaymentSession.STATUS_OPEN, Instant.now().minus(Duration.ofSeconds(1)));
        when(sessionRepository.findById("ps_1")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.tokenize(cardCommand()))
                .isInstanceOf(SessionExpiredException.class);

        verify(sessionRepository).updateStatus("ps_1", PaymentSession.STATUS_EXPIRED);
        verify(sessionRepository, never()).incrementTokenizeAttempts(org.mockito.ArgumentMatchers.anyString());
        verify(tokenRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void completedSession_throws_withoutIncrementOrLock() {
        // status COMPLETE, not expired by time -> isExpired() false, isOpen() false. No status write.
        PaymentSession complete = session(PaymentSession.STATUS_COMPLETE, Instant.now().plus(Duration.ofMinutes(10)));
        when(sessionRepository.findById("ps_1")).thenReturn(Optional.of(complete));

        assertThatThrownBy(() -> service.tokenize(cardCommand()))
                .isInstanceOf(SessionExpiredException.class);

        verify(sessionRepository, never()).updateStatus(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(sessionRepository, never()).incrementTokenizeAttempts(org.mockito.ArgumentMatchers.anyString());
        verify(tokenRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void attemptsEqualToMax_isStillAllowed_tokenCreated() {
        // BOUNDARY: attempts == max is allowed (trip is attempts > max). Token must be minted.
        PaymentSession open = session(PaymentSession.STATUS_OPEN, Instant.now().plus(Duration.ofMinutes(10)));
        when(sessionRepository.findById("ps_1")).thenReturn(Optional.of(open));
        when(sessionRepository.incrementTokenizeAttempts("ps_1")).thenReturn(MAX_ATTEMPTS);

        PaymentToken token = service.tokenize(cardCommand());

        assertThat(token).isNotNull();
        verify(tokenRepository).save(org.mockito.ArgumentMatchers.any(PaymentToken.class));
        // Session NOT locked at exactly max.
        verify(sessionRepository, never()).updateStatus("ps_1", PaymentSession.STATUS_EXPIRED);
    }

    @Test
    void attemptsExceedMax_throwsRateLimit_andLocksSession() {
        // BOUNDARY: attempts == max+1 trips the limit. Session is locked, rate-limit carries the max.
        PaymentSession open = session(PaymentSession.STATUS_OPEN, Instant.now().plus(Duration.ofMinutes(10)));
        when(sessionRepository.findById("ps_1")).thenReturn(Optional.of(open));
        when(sessionRepository.incrementTokenizeAttempts("ps_1")).thenReturn(MAX_ATTEMPTS + 1);

        assertThatThrownBy(() -> service.tokenize(cardCommand()))
                .isInstanceOf(TokenizationRateLimitException.class)
                .satisfies(ex -> assertThat(((TokenizationRateLimitException) ex).getMaxAttempts()).isEqualTo(MAX_ATTEMPTS));

        verify(sessionRepository).updateStatus("ps_1", PaymentSession.STATUS_EXPIRED);
        verify(tokenRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void happyPath_savesSingleUseToken_withExpiryAndCopiedCardFields_serverSideFieldsNull() {
        PaymentSession open = session(PaymentSession.STATUS_OPEN, Instant.now().plus(Duration.ofMinutes(10)));
        when(sessionRepository.findById("ps_1")).thenReturn(Optional.of(open));
        when(sessionRepository.incrementTokenizeAttempts("ps_1")).thenReturn(1);

        Instant before = Instant.now();
        PaymentToken returned = service.tokenize(cardCommand());

        ArgumentCaptor<PaymentToken> cap = ArgumentCaptor.forClass(PaymentToken.class);
        verify(tokenRepository).save(cap.capture());
        PaymentToken saved = cap.getValue();

        // Returned token is the saved token.
        assertThat(returned).isSameAs(saved);

        // Single-use, with single-use expiry window applied.
        assertThat(saved.isSingleUse()).isTrue();
        assertThat(saved.isUsed()).isFalse();
        Instant lower = before.plus(SINGLE_USE_EXPIRY).minusSeconds(5);
        Instant upper = Instant.now().plus(SINGLE_USE_EXPIRY).plusSeconds(5);
        assertThat(saved.getExpiresAt()).isBetween(lower, upper);

        // Card metadata copied from the command.
        assertThat(saved.getTenantId()).isEqualTo("t1");
        assertThat(saved.getSessionId()).isEqualTo("ps_1");
        assertThat(saved.getType()).isEqualTo(PaymentToken.TYPE_CARD);
        assertThat(saved.getCardBrand()).isEqualTo("visa");
        assertThat(saved.getCardLastFour()).isEqualTo("4242");
        assertThat(saved.getCardExpMonth()).isEqualTo(12);
        assertThat(saved.getCardExpYear()).isEqualTo(2030);

        // Server-side fields are NOT set by tokenize (computed/applied downstream).
        assertThat(saved.getCardFingerprint()).isNull();
        assertThat(saved.getEncryptionKeyId()).isNull();

        // ID is the prefixed payment-token id.
        assertThat(saved.getId()).startsWith("ptok_");
    }

    @Test
    void happyPath_singleUseExpiryHonored_notMultiUse() {
        // Guard against an accidental swap to the 365-day window: the gap must be ~15m, far below a day.
        PaymentSession open = session(PaymentSession.STATUS_OPEN, Instant.now().plus(Duration.ofMinutes(10)));
        when(sessionRepository.findById("ps_1")).thenReturn(Optional.of(open));
        when(sessionRepository.incrementTokenizeAttempts("ps_1")).thenReturn(1);

        PaymentToken token = service.tokenize(cardCommand());

        long minutesToExpiry = ChronoUnit.MINUTES.between(token.getCreatedAt(), token.getExpiresAt());
        assertThat(minutesToExpiry).isBetween(14L, 16L);
    }
}
