package io.nexuspay.gateway.application.service;

import io.nexuspay.common.crypto.EncryptionPort;
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
 * path.
 *
 * <p>SEC-04 / B-004: the happy path must NEVER persist a recoverable PAN. The inbound
 * {@code token_data} (the SDK's base64-of-PAN bytes) is encrypted via the {@link EncryptionPort}
 * before storage and {@code encryption_key_id} is set non-null for card-bearing tokens. A regression
 * here lets attackers probe cards, wrongly locks legitimate checkouts, OR re-opens the cleartext-PAN-
 * at-rest hole.
 */
class TokenizationServiceTest {

    private static final int MAX_ATTEMPTS = 10;
    private static final Duration SINGLE_USE_EXPIRY = Duration.ofMinutes(15);
    private static final String KEY_ID = "key-tokenize-001";

    private PaymentSessionRepository sessionRepository;
    private PaymentTokenRepository tokenRepository;
    private EncryptionPort encryption;
    private TokenizationService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(PaymentSessionRepository.class);
        tokenRepository = mock(PaymentTokenRepository.class);
        encryption = mock(EncryptionPort.class);
        // Reversible XOR "cipher" — NOT real crypto, just enough for the unit test to prove
        // (a) the service replaces token_data with whatever the EncryptionPort returns and
        // (b) the original plaintext is recoverable ONLY by applying the key (round-trip),
        // never directly from the stored bytes. The real AES-256-GCM round-trip lives in
        // AesGcmEncryptionAdapterTest + the PanPersistenceRedteamTest integration gate.
        when(encryption.currentKeyId()).thenReturn(KEY_ID);
        when(encryption.encrypt(org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.eq(KEY_ID)))
                .thenAnswer(inv -> new EncryptionPort.EncryptionResult(
                        xorMask(inv.getArgument(0)), KEY_ID));
        service = new TokenizationService(sessionRepository, tokenRepository, encryption,
                MAX_ATTEMPTS, SINGLE_USE_EXPIRY);
    }

    /** Symmetric, reversible byte transform standing in for encrypt/decrypt in the unit test. */
    private static byte[] xorMask(byte[] in) {
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = (byte) (in[i] ^ 0x5A);
        }
        return out;
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

    // Mirrors the on-the-wire shape: the SDK posts base64(PAN) as token_data bytes.
    private static final byte[] SDK_TOKEN_DATA =
            java.util.Base64.getEncoder().encodeToString("4242424242424242".getBytes())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private static TokenizeCommand cardCommand() {
        return new TokenizeCommand(
                "ps_1", "t1", PaymentToken.TYPE_CARD,
                SDK_TOKEN_DATA, "4242", "visa", 12, 2030);
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
    void happyPath_savesSingleUseToken_withExpiryAndCopiedCardFields() {
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

        // Fingerprint is still computed downstream from full card data (option-a does
        // not handle the raw PAN server-side), so it stays null on the SDK path.
        assertThat(saved.getCardFingerprint()).isNull();

        // ID is the prefixed payment-token id.
        assertThat(saved.getId()).startsWith("ptok_");
    }

    @Test
    void happyPath_encryptsTokenData_andSetsEncryptionKeyId_neverStoresRawPan() {
        // SEC-04 / B-004: the inbound token_data (the SDK's base64(PAN) bytes) must be
        // run through the EncryptionPort before persistence and the key id recorded, so
        // a DB read yields only ciphertext.
        PaymentSession open = session(PaymentSession.STATUS_OPEN, Instant.now().plus(Duration.ofMinutes(10)));
        when(sessionRepository.findById("ps_1")).thenReturn(Optional.of(open));
        when(sessionRepository.incrementTokenizeAttempts("ps_1")).thenReturn(1);

        service.tokenize(cardCommand());

        ArgumentCaptor<PaymentToken> cap = ArgumentCaptor.forClass(PaymentToken.class);
        verify(tokenRepository).save(cap.capture());
        PaymentToken saved = cap.getValue();

        // The encryption layer was consulted and the stored value is the ciphertext.
        verify(encryption).encrypt(org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.eq(KEY_ID));
        assertThat(saved.getEncryptionKeyId())
                .as("encryption_key_id must be set for a card-bearing token (the secure invariant)")
                .isEqualTo(KEY_ID);

        // The stored bytes are NOT the raw inbound bytes.
        assertThat(saved.getTokenData())
                .as("token_data must be transformed (encrypted), not the verbatim SDK bytes")
                .isNotEqualTo(SDK_TOKEN_DATA);

        // Encryption round-trip: applying the key recovers the original plaintext, but only
        // via the key — the stored bytes alone are not the cleartext.
        assertThat(xorMask(saved.getTokenData()))
                .as("decrypting the stored ciphertext recovers the original token_data")
                .isEqualTo(SDK_TOKEN_DATA);
    }

    @Test
    void emptyTokenData_isNotEncrypted_andKeyIdStaysNull() {
        // bank_redirect / empty token_data carries no secret — leave it as-is with a null
        // key id (no spurious encryption, no key-id on a non-secret).
        PaymentSession open = session(PaymentSession.STATUS_OPEN, Instant.now().plus(Duration.ofMinutes(10)));
        when(sessionRepository.findById("ps_1")).thenReturn(Optional.of(open));
        when(sessionRepository.incrementTokenizeAttempts("ps_1")).thenReturn(1);

        service.tokenize(new TokenizeCommand(
                "ps_1", "t1", PaymentToken.TYPE_BANK_REDIRECT,
                new byte[0], null, null, null, null));

        ArgumentCaptor<PaymentToken> cap = ArgumentCaptor.forClass(PaymentToken.class);
        verify(tokenRepository).save(cap.capture());
        PaymentToken saved = cap.getValue();

        verify(encryption, never()).encrypt(org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.anyString());
        assertThat(saved.getEncryptionKeyId()).isNull();
        assertThat(saved.getTokenData()).isEmpty();
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
