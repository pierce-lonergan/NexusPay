package io.nexuspay.iam.application;

import io.nexuspay.common.exception.AuthorizationException;
import io.nexuspay.common.exception.InvalidRequestException;
import io.nexuspay.iam.adapter.out.persistence.ApiKeyEntity;
import io.nexuspay.iam.adapter.out.persistence.JpaApiKeyRepository;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DX-5c lifecycle proof: expiry enforcement (fail-CLOSED, no oracle), last_used_at observability
 * (fail-OPEN, throttled), and create-time expiry validation. Mirrors the mock-based style of
 * {@link ApiKeyServiceCollisionTest} / {@link ApiKeyServicePrefixConsistencyTest}: the repository is
 * stubbed so each case isolates the service-layer control without a DB harness.
 */
class ApiKeyServiceLifecycleTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private static final String RAW_TEST = "sk_test_lifek1";

    private ApiKeyEntity entity(String rawKey, boolean live, Instant expiresAt, Instant lastUsedAt) {
        String prefix = rawKey.substring(0, Math.min(rawKey.length(), 12));
        return new ApiKeyEntity("key_life", encoder.encode(rawKey), prefix, "name", "operator",
                "tenant_A", live, Instant.now(), null, expiresAt, lastUsedAt, null);
    }

    private ApiKeyService serviceReturning(List<ApiKeyEntity> candidates, ApiKeyUsageTracker tracker) {
        JpaApiKeyRepository repo = mock(JpaApiKeyRepository.class);
        when(repo.findByKeyPrefixAndRevokedAtIsNull(anyString())).thenReturn(candidates);
        return new ApiKeyService(repo, tracker);
    }

    // --- (1) Expiry: an expired key fails IDENTICALLY to an invalid key (no oracle) ---

    @Test
    void expiredKey_failsWithSameExceptionAsInvalidKey() {
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        ApiKeyEntity expired = entity(RAW_TEST, false, past, null);
        ApiKeyService service = serviceReturning(List.of(expired), mock(ApiKeyUsageTracker.class));

        AuthorizationException onExpired =
                (AuthorizationException) catchAuth(() -> service.authenticate(RAW_TEST));

        // Same code + message as the canonical invalid-key failure -> filter renders an identical 401.
        AuthorizationException reference = AuthorizationException.invalidApiKey();
        assertThat(onExpired.getErrorCode()).isEqualTo("invalid_api_key");
        assertThat(onExpired.getMessage()).isEqualTo(reference.getMessage());

        // And byte-identical to the unknown-key outcome: no expiry oracle distinguishes them.
        ApiKeyService empty = serviceReturning(List.of(), mock(ApiKeyUsageTracker.class));
        AuthorizationException onUnknown =
                (AuthorizationException) catchAuth(() -> empty.authenticate(RAW_TEST));
        assertThat(onExpired.getMessage()).isEqualTo(onUnknown.getMessage());
        assertThat(onExpired.getErrorCode()).isEqualTo(onUnknown.getErrorCode());
    }

    @Test
    void expiryDeadlineInstant_isAlreadyExpired_failClosedAtBoundary() {
        // At-or-after the deadline is expired: the deadline instant itself must NOT authenticate.
        Instant now = Instant.now();
        ApiKeyEntity atBoundary = entity(RAW_TEST, false, now, null);
        // Stub last_used null so the throttle path would touch — but expiry rejects first.
        ApiKeyUsageTracker tracker = mock(ApiKeyUsageTracker.class);
        ApiKeyService service = serviceReturning(List.of(atBoundary), tracker);

        assertThatThrownBy(() -> service.authenticate(RAW_TEST))
                .isInstanceOf(AuthorizationException.class);
        // An expired key must never be stamped as "used".
        verify(tracker, never()).touch(anyString(), any());
    }

    // --- (2) A non-expired key with a future expiry authenticates ---

    @Test
    void futureExpiry_authenticates() {
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
        ApiKeyEntity valid = entity(RAW_TEST, false, future, null);
        ApiKeyService service = serviceReturning(List.of(valid), mock(ApiKeyUsageTracker.class));

        NexusPayPrincipal principal = service.authenticate(RAW_TEST);
        assertThat(principal).isNotNull();
        assertThat(principal.userId()).isEqualTo("key_life");
        assertThat(principal.live()).isFalse();
    }

    @Test
    void nullExpiry_neverExpires_authenticates() {
        ApiKeyEntity perpetual = entity(RAW_TEST, false, null, null);
        ApiKeyService service = serviceReturning(List.of(perpetual), mock(ApiKeyUsageTracker.class));

        assertThat(service.authenticate(RAW_TEST)).isNotNull();
    }

    // --- (3) createApiKey rejects an expiresAt at-or-before now ---

    @Test
    void createApiKey_rejectsPastExpiry() {
        JpaApiKeyRepository repo = mock(JpaApiKeyRepository.class);
        ApiKeyService service = new ApiKeyService(repo, mock(ApiKeyUsageTracker.class));

        Instant past = Instant.now().minus(1, ChronoUnit.MINUTES);
        assertThatThrownBy(() ->
                service.createApiKey("k", "operator", "tenant_A", false, past))
                .isInstanceOf(InvalidRequestException.class);

        // Fail-closed: nothing persisted.
        verify(repo, never()).save(any());
    }

    @Test
    void createApiKey_acceptsFutureExpiry_persistsIt() {
        JpaApiKeyRepository repo = mock(JpaApiKeyRepository.class);
        ApiKeyService service = new ApiKeyService(repo, mock(ApiKeyUsageTracker.class));

        Instant future = Instant.now().plus(30, ChronoUnit.DAYS);
        ApiKeyService.CreateApiKeyResult result =
                service.createApiKey("k", "operator", "tenant_A", false, future);

        assertThat(result.expiresAt()).isEqualTo(future);
        ArgumentCaptor<ApiKeyEntity> saved = ArgumentCaptor.forClass(ApiKeyEntity.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(future);
    }

    @Test
    void createApiKey_backCompatOverload_neverExpires() {
        JpaApiKeyRepository repo = mock(JpaApiKeyRepository.class);
        ApiKeyService service = new ApiKeyService(repo, mock(ApiKeyUsageTracker.class));

        ApiKeyService.CreateApiKeyResult result =
                service.createApiKey("k", "operator", "tenant_A", false);

        assertThat(result.expiresAt()).isNull();
        ArgumentCaptor<ApiKeyEntity> saved = ArgumentCaptor.forClass(ApiKeyEntity.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getExpiresAt()).isNull();
    }

    // --- (4) last_used_at: stamped on success; throttled re-auth does not error ---

    @Test
    void successfulAuthenticate_stampsLastUsed_whenNeverUsed() {
        ApiKeyEntity neverUsed = entity(RAW_TEST, false, null, null);
        ApiKeyUsageTracker tracker = mock(ApiKeyUsageTracker.class);
        ApiKeyService service = serviceReturning(List.of(neverUsed), tracker);

        service.authenticate(RAW_TEST);

        verify(tracker, atLeastOnce()).touch(eq("key_life"), any(Instant.class));
    }

    @Test
    void freshlyUsedKey_withinThrottleWindow_isNotRetouched_andDoesNotError() {
        // last_used 1 minute ago is within the 5-minute throttle window -> no re-touch.
        Instant recent = Instant.now().minus(1, ChronoUnit.MINUTES);
        ApiKeyEntity recentlyUsed = entity(RAW_TEST, false, null, recent);
        ApiKeyUsageTracker tracker = mock(ApiKeyUsageTracker.class);
        ApiKeyService service = serviceReturning(List.of(recentlyUsed), tracker);

        NexusPayPrincipal principal = service.authenticate(RAW_TEST);
        assertThat(principal).isNotNull(); // authenticates fine
        verify(tracker, never()).touch(anyString(), any());
    }

    @Test
    void staleLastUsed_outsideThrottleWindow_isRetouched() {
        Instant stale = Instant.now().minus(10, ChronoUnit.MINUTES);
        ApiKeyEntity staleUsed = entity(RAW_TEST, false, null, stale);
        ApiKeyUsageTracker tracker = mock(ApiKeyUsageTracker.class);
        ApiKeyService service = serviceReturning(List.of(staleUsed), tracker);

        service.authenticate(RAW_TEST);
        verify(tracker, atLeastOnce()).touch(eq("key_life"), any(Instant.class));
    }

    @Test
    void touchFailure_isSwallowed_keyStillAuthenticates() {
        ApiKeyEntity neverUsed = entity(RAW_TEST, false, null, null);
        ApiKeyUsageTracker tracker = mock(ApiKeyUsageTracker.class);
        // The tracker throws — must NOT deny a valid key (fail-open observability).
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(tracker).touch(anyString(), any());
        ApiKeyService service = serviceReturning(List.of(neverUsed), tracker);

        NexusPayPrincipal principal = service.authenticate(RAW_TEST);
        assertThat(principal).as("a touch failure must never deny a valid key").isNotNull();
        assertThat(principal.userId()).isEqualTo("key_life");
    }

    private Throwable catchAuth(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            return t;
        }
        throw new AssertionError("expected AuthorizationException but none was thrown");
    }
}
