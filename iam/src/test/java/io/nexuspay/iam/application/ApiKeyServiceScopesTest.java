package io.nexuspay.iam.application;

import io.nexuspay.common.exception.InvalidRequestException;
import io.nexuspay.iam.adapter.out.persistence.ApiKeyEntity;
import io.nexuspay.iam.adapter.out.persistence.JpaApiKeyRepository;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DX-5c-ii: per-key SCOPES end-to-end at the service layer.
 *
 * <ul>
 *   <li>authenticate yields an UNRESTRICTED principal for a NULL-scopes key (back-compat) and a
 *       RESTRICTED principal for a scoped key; {@code hasScope} semantics hold either way.</li>
 *   <li>createApiKey rejects an unknown scope (fail-closed, nothing persisted) and persists a valid CSV.</li>
 *   <li>rotateApiKey INHERITS the rotated key's scopes verbatim — a rotation never widens scope.</li>
 * </ul>
 */
class ApiKeyServiceScopesTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private ApiKeyEntity entity(String rawKey, boolean live, String scopesCsv) {
        String prefix = rawKey.substring(0, Math.min(rawKey.length(), 12));
        return new ApiKeyEntity("key_1", encoder.encode(rawKey), prefix, "name", "operator",
                "tenant_1", live, Instant.now(), null, null, null, null, scopesCsv);
    }

    private ApiKeyService serviceReturning(ApiKeyEntity entity) {
        JpaApiKeyRepository repo = mock(JpaApiKeyRepository.class);
        when(repo.findByKeyPrefixAndRevokedAtIsNull(anyString())).thenReturn(List.of(entity));
        return new ApiKeyService(repo, mock(ApiKeyUsageTracker.class));
    }

    // --- authenticate: back-compat (null scopes) == unrestricted ---

    @Test
    void authenticate_nullScopes_yieldsUnrestrictedPrincipal() {
        String rawKey = "sk_test_scope1";
        NexusPayPrincipal principal = serviceReturning(entity(rawKey, false, null)).authenticate(rawKey);

        assertThat(principal).isNotNull();
        assertThat(principal.scopes()).as("null scopes column -> unrestricted").isNull();
        // hasScope is true for ANY scope when unrestricted (back-compat).
        assertThat(principal.hasScope("payments:write")).isTrue();
        assertThat(principal.hasScope("anything:else")).isTrue();
    }

    @Test
    void authenticate_emptyScopes_yieldsUnrestrictedPrincipal() {
        String rawKey = "sk_test_scope2";
        NexusPayPrincipal principal = serviceReturning(entity(rawKey, false, "  ")).authenticate(rawKey);

        assertThat(principal.scopes()).isNull();
        assertThat(principal.hasScope("payments:write")).isTrue();
    }

    // --- authenticate: scoped key == restricted ---

    @Test
    void authenticate_scopedKey_yieldsRestrictedPrincipal() {
        String rawKey = "sk_test_scope3";
        NexusPayPrincipal principal =
                serviceReturning(entity(rawKey, false, "payments:read")).authenticate(rawKey);

        assertThat(principal.scopes()).containsExactly("payments:read");
        assertThat(principal.hasScope("payments:read")).as("granted scope -> true").isTrue();
        assertThat(principal.hasScope("payments:write")).as("ungranted scope -> false (fail-closed)").isFalse();
    }

    @Test
    void authenticate_persistedUnknownToken_isFilteredDefensively_neverThrows() {
        // A drifted/legacy persisted value with an unknown token must NOT 400 the auth path; the unknown
        // token is dropped, the known one kept. If ALL tokens were unknown the principal is unrestricted.
        String rawKey = "sk_test_scope4";
        NexusPayPrincipal principal =
                serviceReturning(entity(rawKey, false, "payments:read,legacy:bogus")).authenticate(rawKey);

        assertThat(principal.scopes()).containsExactly("payments:read");
        assertThat(principal.hasScope("payments:read")).isTrue();
        assertThat(principal.hasScope("legacy:bogus")).isFalse();
    }

    // --- createApiKey: validation + persistence ---

    @Test
    void createApiKey_rejectsUnknownScope_failClosed_nothingPersisted() {
        JpaApiKeyRepository repo = mock(JpaApiKeyRepository.class);
        ApiKeyService service = new ApiKeyService(repo, mock(ApiKeyUsageTracker.class));

        assertThatThrownBy(() -> service.createApiKey(
                "k", "operator", "tenant_A", false, null, Set.of("payments:read", "totally:bogus")))
                .isInstanceOf(InvalidRequestException.class);

        verify(repo, never()).save(any());
    }

    @Test
    void createApiKey_persistsCanonicalCsv_andResultCarriesScopes() {
        JpaApiKeyRepository repo = mock(JpaApiKeyRepository.class);
        ApiKeyService service = new ApiKeyService(repo, mock(ApiKeyUsageTracker.class));

        ApiKeyService.CreateApiKeyResult result = service.createApiKey(
                "k", "operator", "tenant_A", false, null, Set.of("payments:write", "payments:read"));

        ArgumentCaptor<ApiKeyEntity> saved = ArgumentCaptor.forClass(ApiKeyEntity.class);
        verify(repo).save(saved.capture());
        // Canonical (declaration) order persisted.
        assertThat(saved.getValue().getScopes()).isEqualTo("payments:read,payments:write");
        assertThat(result.scopes()).containsExactlyInAnyOrder("payments:read", "payments:write");
    }

    @Test
    void createApiKey_backCompatNoScopes_persistsNull_unrestricted() {
        JpaApiKeyRepository repo = mock(JpaApiKeyRepository.class);
        ApiKeyService service = new ApiKeyService(repo, mock(ApiKeyUsageTracker.class));

        ApiKeyService.CreateApiKeyResult result =
                service.createApiKey("k", "operator", "tenant_A", false);

        ArgumentCaptor<ApiKeyEntity> saved = ArgumentCaptor.forClass(ApiKeyEntity.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getScopes()).isNull();
        assertThat(result.scopes()).isNull();
    }

    // --- rotate: inherits scopes, never widens ---

    @Test
    void rotate_inheritsScopesVerbatim_neverWidens() {
        // In-memory repo so the rotated key can be saved + the new one inspected.
        JpaApiKeyRepository repo = mock(JpaApiKeyRepository.class);
        List<ApiKeyEntity> store = new ArrayList<>();
        when(repo.save(any(ApiKeyEntity.class))).thenAnswer(inv -> {
            ApiKeyEntity e = inv.getArgument(0);
            store.removeIf(x -> x.getId().equals(e.getId()));
            store.add(e);
            return e;
        });
        when(repo.findByIdAndTenantId(anyString(), anyString())).thenAnswer(inv ->
                store.stream().filter(x -> x.getId().equals(inv.getArgument(0))
                        && x.getTenantId().equals(inv.getArgument(1))).findFirst());

        ApiKeyService service = new ApiKeyService(repo, mock(ApiKeyUsageTracker.class));

        ApiKeyEntity old = new ApiKeyEntity("key_old", encoder.encode("sk_test_rotsc"), "sk_test_rot",
                "name", "operator", "tenant_A", false, Instant.now(), null, null, null, null,
                "payments:read,refunds:read");
        store.add(old);

        ApiKeyService.CreateApiKeyResult rotated =
                service.rotateApiKey("key_old", "tenant_A", Duration.ofHours(1));

        // The successor's persisted scopes EQUAL the old key's scopes — no widening, no change.
        ApiKeyEntity successor = store.stream()
                .filter(e -> e.getId().equals(rotated.id())).findFirst().orElseThrow();
        assertThat(successor.getScopes()).isEqualTo("payments:read,refunds:read");
        assertThat(rotated.scopes()).containsExactlyInAnyOrder("payments:read", "refunds:read");
    }
}
