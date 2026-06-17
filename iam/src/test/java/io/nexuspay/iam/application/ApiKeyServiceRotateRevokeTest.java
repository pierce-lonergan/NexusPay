package io.nexuspay.iam.application;

import io.nexuspay.common.exception.AuthorizationException;
import io.nexuspay.common.exception.ConflictException;
import io.nexuspay.iam.adapter.out.persistence.ApiKeyEntity;
import io.nexuspay.iam.adapter.out.persistence.JpaApiKeyRepository;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DX-5c rotate-with-overlap + revoke-IDOR proof.
 *
 * <p>Rotation: the NEW key authenticates; the OLD key still authenticates DURING the overlap window and
 * fails AFTER the overlap deadline; {@code old.replaced_by == new id}; an existing expiry SOONER than
 * {@code now+overlap} is NOT extended; re-rotating an already-replaced key throws.
 *
 * <p>Revoke IDOR (SEC fix): a tenant-A admin revoking a tenant-B key id throws not-found (uniform
 * {@code invalid_api_key}, no oracle) and the tenant-B key REMAINS active (still authenticates).
 *
 * <p>The repository is stubbed (mock-based, no DB harness) like the sibling iam service tests. A small
 * in-memory store backs {@code save}/{@code findByIdAndTenantId}/{@code findByKeyPrefixAndRevokedAtIsNull}
 * so the freshly-minted new key (whose random secret we cannot predict) can be looked up by prefix and
 * re-authenticated end-to-end.
 */
class ApiKeyServiceRotateRevokeTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /** Mock repo backed by an in-memory list so saved entities can be re-found by prefix and by id. */
    private static final class StoreRepo {
        final JpaApiKeyRepository mock = mock(JpaApiKeyRepository.class);
        final List<ApiKeyEntity> store = new ArrayList<>();

        StoreRepo() {
            when(mock.save(any(ApiKeyEntity.class))).thenAnswer(inv -> {
                ApiKeyEntity e = inv.getArgument(0);
                store.removeIf(x -> x.getId().equals(e.getId()));
                store.add(e);
                return e;
            });
            when(mock.findByIdAndTenantId(anyString(), anyString())).thenAnswer(inv -> {
                String id = inv.getArgument(0);
                String tenant = inv.getArgument(1);
                return store.stream()
                        .filter(x -> x.getId().equals(id) && x.getTenantId().equals(tenant))
                        .findFirst();
            });
            when(mock.findByKeyPrefixAndRevokedAtIsNull(anyString())).thenAnswer(inv -> {
                String prefix = inv.getArgument(0);
                return store.stream()
                        .filter(x -> x.getKeyPrefix().equals(prefix) && x.getRevokedAt() == null)
                        .toList();
            });
        }

        void seed(ApiKeyEntity e) {
            store.add(e);
        }
    }

    private ApiKeyEntity entity(String id, String rawKey, boolean live, String tenant,
                                Instant expiresAt) {
        String prefix = rawKey.substring(0, Math.min(rawKey.length(), 12));
        return new ApiKeyEntity(id, encoder.encode(rawKey), prefix, "name", "operator",
                tenant, live, Instant.now(), null, expiresAt, null, null);
    }

    // --- Rotation happy path: new key authenticates; old key works during overlap, fails after ---

    @Test
    void rotate_newKeyAuthenticates_oldKeyWorksDuringOverlap_failsAfter() {
        StoreRepo repo = new StoreRepo();
        ApiKeyService service = new ApiKeyService(repo.mock, mock(ApiKeyUsageTracker.class));

        // Old key: never-expiring sk_test_ key in tenant_A. Use a distinct 12-char prefix from the new key.
        ApiKeyEntity old = entity("key_old", "sk_test_oldk1", false, "tenant_A", null);
        repo.seed(old);

        Duration overlap = Duration.ofHours(1);
        ApiKeyService.CreateApiKeyResult rotated = service.rotateApiKey("key_old", "tenant_A", overlap);

        // (a) The NEW key authenticates.
        NexusPayPrincipal viaNew = service.authenticate(rotated.fullKey());
        assertThat(viaNew).isNotNull();
        assertThat(viaNew.userId()).isEqualTo(rotated.id());
        assertThat(viaNew.tenantId()).isEqualTo("tenant_A");

        // (b) The OLD key still authenticates DURING the overlap window (its new expiry is now+1h).
        NexusPayPrincipal viaOld = service.authenticate("sk_test_oldk1");
        assertThat(viaOld).as("old key works during overlap").isNotNull();
        assertThat(viaOld.userId()).isEqualTo("key_old");

        // (c) old.replaced_by == new id, and the old key's expiry is the overlap deadline (~now+1h).
        assertThat(old.getReplacedBy()).isEqualTo(rotated.id());
        assertThat(old.getExpiresAt()).isNotNull();
        Instant approxDeadline = Instant.now().plus(1, ChronoUnit.HOURS);
        assertThat(old.getExpiresAt())
                .isBetween(approxDeadline.minus(5, ChronoUnit.MINUTES),
                        approxDeadline.plus(5, ChronoUnit.MINUTES));

        // (d) AFTER the overlap deadline the old key fails (simulate by pulling the deadline into the past).
        old.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        assertThatThrownBy(() -> service.authenticate("sk_test_oldk1"))
                .isInstanceOf(AuthorizationException.class)
                .extracting(e -> ((AuthorizationException) e).getErrorCode())
                .isEqualTo("invalid_api_key");
        // The new key still authenticates after the old one has retired.
        assertThat(service.authenticate(rotated.fullKey())).isNotNull();
    }

    @Test
    void rotate_zeroOverlap_retiresOldKeyImmediately() {
        StoreRepo repo = new StoreRepo();
        ApiKeyService service = new ApiKeyService(repo.mock, mock(ApiKeyUsageTracker.class));

        ApiKeyEntity old = entity("key_old", "sk_test_oldk2", false, "tenant_A", null);
        repo.seed(old);

        Instant before = Instant.now();
        service.rotateApiKey("key_old", "tenant_A", Duration.ZERO);

        // expires_at = now (immediate retirement) -> at-or-after now -> old key no longer authenticates.
        assertThat(old.getExpiresAt()).isBetween(before.minus(5, ChronoUnit.SECONDS),
                Instant.now().plus(5, ChronoUnit.SECONDS));
        assertThatThrownBy(() -> service.authenticate("sk_test_oldk2"))
                .isInstanceOf(AuthorizationException.class);
    }

    // --- Rotation never extends a sooner existing expiry ---

    @Test
    void rotate_doesNotExtendExpiry_whenExistingExpiryIsSoonerThanOverlap() {
        StoreRepo repo = new StoreRepo();
        ApiKeyService service = new ApiKeyService(repo.mock, mock(ApiKeyUsageTracker.class));

        // Old key already expires in 10 minutes; overlap is 1 hour. The EARLIER (10 min) must win.
        Instant soonExpiry = Instant.now().plus(10, ChronoUnit.MINUTES);
        ApiKeyEntity old = entity("key_old", "sk_test_oldk3", false, "tenant_A", soonExpiry);
        repo.seed(old);

        service.rotateApiKey("key_old", "tenant_A", Duration.ofHours(1));

        assertThat(old.getExpiresAt())
                .as("rotation must not lengthen a key that was going to expire sooner")
                .isEqualTo(soonExpiry);
    }

    @Test
    void rotate_newKeyInheritsOldOriginalExpiry() {
        StoreRepo repo = new StoreRepo();
        ApiKeyService service = new ApiKeyService(repo.mock, mock(ApiKeyUsageTracker.class));

        Instant originalExpiry = Instant.now().plus(90, ChronoUnit.DAYS);
        ApiKeyEntity old = entity("key_old", "sk_test_oldk4", false, "tenant_A", originalExpiry);
        repo.seed(old);

        ApiKeyService.CreateApiKeyResult rotated =
                service.rotateApiKey("key_old", "tenant_A", Duration.ofHours(1));

        // New key inherits the old key's ORIGINAL expiry (90 days out), not the shortened overlap deadline.
        assertThat(rotated.expiresAt()).isEqualTo(originalExpiry);
    }

    // --- Re-rotating an already-replaced key throws ---

    @Test
    void rotate_alreadyReplacedKey_throws() {
        StoreRepo repo = new StoreRepo();
        ApiKeyService service = new ApiKeyService(repo.mock, mock(ApiKeyUsageTracker.class));

        ApiKeyEntity old = entity("key_old", "sk_test_oldk5", false, "tenant_A", null);
        repo.seed(old);

        service.rotateApiKey("key_old", "tenant_A", Duration.ofHours(1)); // first rotation sets replaced_by

        assertThatThrownBy(() -> service.rotateApiKey("key_old", "tenant_A", Duration.ofHours(1)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void rotate_otherTenantKeyId_throwsNotFound_noOracle() {
        StoreRepo repo = new StoreRepo();
        ApiKeyService service = new ApiKeyService(repo.mock, mock(ApiKeyUsageTracker.class));

        // Key lives in tenant_B; a tenant_A actor must not be able to rotate it.
        ApiKeyEntity tenantBkey = entity("key_B", "sk_test_btk1", false, "tenant_B", null);
        repo.seed(tenantBkey);

        assertThatThrownBy(() -> service.rotateApiKey("key_B", "tenant_A", Duration.ofHours(1)))
                .isInstanceOf(AuthorizationException.class)
                .extracting(e -> ((AuthorizationException) e).getErrorCode())
                .isEqualTo("invalid_api_key");
    }

    // --- Revoke IDOR: tenant-A admin cannot revoke a tenant-B key; tenant-B key stays active ---

    @Test
    void revoke_otherTenantKey_throwsNotFound_andTenantBKeyRemainsActive() {
        StoreRepo repo = new StoreRepo();
        ApiKeyService service = new ApiKeyService(repo.mock, mock(ApiKeyUsageTracker.class));

        ApiKeyEntity tenantBkey = entity("key_B", "sk_test_btk2", false, "tenant_B", null);
        repo.seed(tenantBkey);

        // tenant_A admin tries to revoke tenant_B's key by id -> uniform not-found (no oracle).
        assertThatThrownBy(() -> service.revokeApiKey("key_B", "tenant_A"))
                .isInstanceOf(AuthorizationException.class)
                .extracting(e -> ((AuthorizationException) e).getErrorCode())
                .isEqualTo("invalid_api_key");

        // The tenant-B key was NOT revoked: still authenticates, revoked_at untouched.
        assertThat(tenantBkey.getRevokedAt()).isNull();
        assertThat(service.authenticate("sk_test_btk2"))
                .as("tenant-B key must remain active after the cross-tenant revoke attempt")
                .isNotNull();
    }

    @Test
    void revoke_ownTenantKey_succeeds() {
        StoreRepo repo = new StoreRepo();
        ApiKeyService service = new ApiKeyService(repo.mock, mock(ApiKeyUsageTracker.class));

        ApiKeyEntity ownKey = entity("key_A", "sk_test_atk1", false, "tenant_A", null);
        repo.seed(ownKey);

        service.revokeApiKey("key_A", "tenant_A");

        assertThat(ownKey.getRevokedAt()).isNotNull();
        verify(repo.mock).findByIdAndTenantId(eq("key_A"), eq("tenant_A"));
    }

    @Test
    void revoke_missingKey_throwsNotFound() {
        StoreRepo repo = new StoreRepo();
        ApiKeyService service = new ApiKeyService(repo.mock, mock(ApiKeyUsageTracker.class));

        assertThatThrownBy(() -> service.revokeApiKey("key_nope", "tenant_A"))
                .isInstanceOf(AuthorizationException.class);
        verify(repo.mock, never()).save(any());
    }
}
