package io.nexuspay.gateway.application.service;

import io.nexuspay.common.domain.SessionToken;
import io.nexuspay.gateway.application.port.in.CreatePaymentSessionUseCase.CreateSessionCommand;
import io.nexuspay.gateway.application.port.in.CreatePaymentSessionUseCase.CreateSessionResult;
import io.nexuspay.gateway.application.port.out.PaymentSessionRepository;
import io.nexuspay.gateway.domain.PaymentSession;
import io.nexuspay.gateway.domain.SessionExpiredException;
import io.nexuspay.iam.application.service.SessionTokenIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-014: {@link PaymentSessionService} owns checkout session lifecycle — a SecureRandom clientSecret,
 * lazy expiration on every read, and an expired-session guard on completeSession (blocks completing a
 * dead/replayed session). These are money/security boundaries, so each is asserted concretely.
 */
class PaymentSessionServiceTest {

    private static final Duration DEFAULT_EXPIRY = Duration.ofMinutes(30);

    private PaymentSessionRepository repository;
    private SessionTokenIssuer tokenIssuer;
    private PaymentSessionService service;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentSessionRepository.class);
        tokenIssuer = mock(SessionTokenIssuer.class);
        service = new PaymentSessionService(repository, tokenIssuer, DEFAULT_EXPIRY);
        // repo.save echoes its argument back, matching JPA-style adapters.
        when(repository.save(any(PaymentSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tokenIssuer.issueToken(anyString(), anyString()))
                .thenAnswer(inv -> new SessionToken("jwt", inv.getArgument(0), inv.getArgument(1),
                        Instant.now().plus(DEFAULT_EXPIRY)));
    }

    private static CreateSessionCommand command(List<String> methods) {
        return new CreateSessionCommand(
                "t1", 12345L, "EUR", "cus_1",
                "https://ok", "https://cancel",
                methods, Map.of("logo", "x"), Map.of("order", "o1"));
    }

    private static PaymentSession session(String status, Instant expiresAt) {
        Instant created = Instant.now().minus(Duration.ofMinutes(1));
        return new PaymentSession(
                "ps_1", "t1", null, "secret",
                12345L, "EUR", status, "cus_1",
                List.of("card"), "https://ok", "https://cancel",
                Map.of(), Map.of(),
                0, expiresAt, created, created);
    }

    // ---- create ----

    @Test
    void create_persistsSession_issuesToken_copiesMoneyFields_andSetsExpiry() {
        Instant before = Instant.now();
        CreateSessionResult result = service.create(command(List.of("card", "apple_pay")));

        ArgumentCaptor<PaymentSession> cap = ArgumentCaptor.forClass(PaymentSession.class);
        verify(repository).save(cap.capture());
        PaymentSession saved = cap.getValue();

        // Money fields copied through.
        assertThat(saved.getAmount()).isEqualTo(12345L);
        assertThat(saved.getCurrency()).isEqualTo("EUR");
        assertThat(saved.getTenantId()).isEqualTo("t1");
        assertThat(saved.getStatus()).isEqualTo(PaymentSession.STATUS_OPEN);
        assertThat(saved.getAllowedPaymentMethods()).containsExactly("card", "apple_pay");

        // Expiry == now + defaultExpiry (tolerance).
        assertThat(saved.getExpiresAt()).isBetween(
                before.plus(DEFAULT_EXPIRY).minusSeconds(5),
                Instant.now().plus(DEFAULT_EXPIRY).plusSeconds(5));

        // Result carries both the session and the issued token.
        assertThat(result.session()).isSameAs(saved);
        assertThat(result.token().token()).isEqualTo("jwt");
        verify(tokenIssuer).issueToken(saved.getId(), "t1");
    }

    @Test
    void create_defaultsToCard_whenMethodsNull() {
        CreateSessionResult result = service.create(command(null));
        assertThat(result.session().getAllowedPaymentMethods()).containsExactly("card");
    }

    @Test
    void create_defaultsToCard_whenMethodsEmpty() {
        CreateSessionResult result = service.create(command(List.of()));
        assertThat(result.session().getAllowedPaymentMethods()).containsExactly("card");
    }

    @Test
    void create_generatesNonBlankAndUniqueClientSecrets() {
        String secret1 = service.create(command(null)).session().getClientSecret();
        String secret2 = service.create(command(null)).session().getClientSecret();

        assertThat(secret1).isNotBlank();
        assertThat(secret2).isNotBlank();
        assertThat(secret1).as("SecureRandom must not repeat across creates").isNotEqualTo(secret2);
    }

    // ---- findById lazy expiration ----

    @Test
    void findById_openButPastExpiry_lazilyExpiresAndReturnsExpiredStatus() {
        PaymentSession stale = session(PaymentSession.STATUS_OPEN, Instant.now().minus(Duration.ofSeconds(1)));
        when(repository.findById("ps_1")).thenReturn(Optional.of(stale));

        Optional<PaymentSession> result = service.findById("ps_1");

        verify(repository).updateStatus("ps_1", PaymentSession.STATUS_EXPIRED);
        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(PaymentSession.STATUS_EXPIRED);
    }

    @Test
    void findById_openAndLive_noLazyExpiration() {
        PaymentSession live = session(PaymentSession.STATUS_OPEN, Instant.now().plus(Duration.ofMinutes(10)));
        when(repository.findById("ps_1")).thenReturn(Optional.of(live));

        Optional<PaymentSession> result = service.findById("ps_1");

        verify(repository, never()).updateStatus(anyString(), anyString());
        assertThat(result).get().extracting(PaymentSession::getStatus).isEqualTo(PaymentSession.STATUS_OPEN);
    }

    @Test
    void findById_missing_returnsEmpty() {
        when(repository.findById("nope")).thenReturn(Optional.empty());
        assertThat(service.findById("nope")).isEmpty();
    }

    // ---- completeSession ----

    @Test
    void completeSession_live_marksCompleteAndSaves() {
        PaymentSession live = session(PaymentSession.STATUS_OPEN, Instant.now().plus(Duration.ofMinutes(10)));
        when(repository.findById("ps_1")).thenReturn(Optional.of(live));

        service.completeSession("ps_1", "pi_99");

        assertThat(live.getStatus()).isEqualTo(PaymentSession.STATUS_COMPLETE);
        assertThat(live.getPaymentIntentId()).isEqualTo("pi_99");
        verify(repository).save(live);
    }

    @Test
    void completeSession_expired_throws_andDoesNotSave() {
        PaymentSession expired = session(PaymentSession.STATUS_OPEN, Instant.now().minus(Duration.ofSeconds(1)));
        when(repository.findById("ps_1")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.completeSession("ps_1", "pi_99"))
                .isInstanceOf(SessionExpiredException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void completeSession_missing_throwsIllegalArgument() {
        when(repository.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.completeSession("nope", "pi_99"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- expireSession ----

    @Test
    void expireSession_open_flipsToExpired() {
        PaymentSession open = session(PaymentSession.STATUS_OPEN, Instant.now().plus(Duration.ofMinutes(10)));
        when(repository.findById("ps_1")).thenReturn(Optional.of(open));

        service.expireSession("ps_1");

        verify(repository).updateStatus("ps_1", PaymentSession.STATUS_EXPIRED);
    }

    @Test
    void expireSession_alreadyComplete_isNoOp() {
        PaymentSession complete = session(PaymentSession.STATUS_COMPLETE, Instant.now().plus(Duration.ofMinutes(10)));
        when(repository.findById("ps_1")).thenReturn(Optional.of(complete));

        service.expireSession("ps_1");

        verify(repository, never()).updateStatus(anyString(), anyString());
    }

    @Test
    void expireSession_missing_throwsIllegalArgument() {
        when(repository.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.expireSession("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
