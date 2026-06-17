package io.nexuspay.iam.application;

import io.nexuspay.common.exception.AuthorizationException;
import io.nexuspay.iam.adapter.out.persistence.ApiKeyEntity;
import io.nexuspay.iam.adapter.out.persistence.JpaApiKeyRepository;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SEC-22 / INT-10 regression proof: two un-revoked API keys sharing the 12-char {@code key_prefix}
 * must NOT collide into a self-inflicted 401 DoS. The finder now returns a {@code List} and
 * {@code authenticate} candidate-iterates with bcrypt {@code matches}; the no-match terminal path is a
 * single uniform {@link AuthorizationException#invalidApiKey()} (no existence oracle), and revoked
 * siblings stay excluded by the {@code AndRevokedAtIsNull} query clause.
 *
 * <p>Each case fails if the fix is reverted (old {@code Optional} finder throws
 * {@code IncorrectResultSizeDataAccessException} on >1 row, an early-mismatch-throws variant 401s on
 * whichever row orders first, dropping {@code AndRevokedAtIsNull} re-admits revoked keys, or a future
 * candidate-count signal breaks the uniform failure).</p>
 */
class ApiKeyServiceCollisionTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    // Two raw keys whose first 12 chars collide: "sk_test_coll" (8 + 4) is exactly PREFIX_DISPLAY_LENGTH.
    // BOTH are sk_test_ keys with is_live=false: sk_live_ vs sk_test_ differ at char 4, so two keys can
    // only share a 12-char prefix WITHIN one mode. DX-3's fail-closed prefix<->is_live invariant forbids
    // the prior cross-mode pairing (sk_test_ prefix + is_live=true), so the colliding siblings are
    // same-mode here — preserving the >1-candidate iteration coverage without violating the new control.
    private static final String RAW_A = "sk_test_coll_a";
    private static final String RAW_B = "sk_test_coll_b";
    // A third key on the SAME colliding prefix that matches NEITHER stored hash.
    private static final String RAW_WRONG = "sk_test_coll_z";

    private ApiKeyEntity entity(String rawKey, boolean live, String id, String role, String tenant) {
        String prefix = rawKey.substring(0, Math.min(rawKey.length(), 12));
        return new ApiKeyEntity(id, encoder.encode(rawKey), prefix, "name", role,
                tenant, live, Instant.now(), null);
    }

    private ApiKeyService serviceReturning(List<ApiKeyEntity> candidates) {
        JpaApiKeyRepository repo = mock(JpaApiKeyRepository.class);
        when(repo.findByKeyPrefixAndRevokedAtIsNull(anyString())).thenReturn(candidates);
        return new ApiKeyService(repo, mock(ApiKeyUsageTracker.class));
    }

    // (a) Collision: each key authenticates to ITS OWN principal (the regression — currently 401s).
    @Test
    void collidingPrefix_eachKeyAuthenticatesToItsOwnPrincipal() {
        ApiKeyEntity a = entity(RAW_A, false, "key_A", "operator", "tenant_A");
        // Same-mode sibling (sk_test_ prefix + is_live=false): collides on "sk_test_coll" yet stays
        // consistent under DX-3's prefix<->is_live invariant. Distinct id/role/tenant still prove each
        // candidate resolves to its OWN principal across a genuine >1-element finder result.
        ApiKeyEntity b = entity(RAW_B, false, "key_B", "admin", "tenant_B");

        // Both un-revoked keys share the same 12-char prefix -> finder returns BOTH.
        ApiKeyService service = serviceReturning(List.of(a, b));

        NexusPayPrincipal pa = service.authenticate(RAW_A);
        assertThat(pa).isNotNull();
        assertThat(pa.userId()).isEqualTo("key_A");
        assertThat(pa.tenantId()).isEqualTo("tenant_A");
        assertThat(pa.role()).isEqualTo("operator");
        assertThat(pa.live()).as("A is sk_test_ / is_live=false -> TEST principal").isFalse();
        // (d) auth method + session-id invariants on the matched principal.
        assertThat(pa.authMethod()).isEqualTo(NexusPayPrincipal.AuthMethod.API_KEY);
        assertThat(pa.sessionId()).isNull();

        NexusPayPrincipal pb = service.authenticate(RAW_B);
        assertThat(pb).isNotNull();
        assertThat(pb.userId()).isEqualTo("key_B");
        assertThat(pb.tenantId()).isEqualTo("tenant_B");
        assertThat(pb.role()).isEqualTo("admin");
        assertThat(pb.live()).as("B is sk_test_ / is_live=false -> TEST principal").isFalse();
        assertThat(pb.authMethod()).isEqualTo(NexusPayPrincipal.AuthMethod.API_KEY);
        assertThat(pb.sessionId()).isNull();
    }

    // (a, cont.) Return-order independence: stub B-then-A and A still resolves to A (no first-candidate bias).
    @Test
    void collidingPrefix_returnOrderDoesNotBiasMatch() {
        ApiKeyEntity a = entity(RAW_A, false, "key_A", "operator", "tenant_A");
        ApiKeyEntity b = entity(RAW_B, false, "key_B", "admin", "tenant_B");

        ApiKeyService service = serviceReturning(List.of(b, a)); // reversed order

        NexusPayPrincipal pa = service.authenticate(RAW_A);
        assertThat(pa.userId()).as("A resolves to A regardless of candidate order").isEqualTo("key_A");
        assertThat(pa.live()).isFalse();

        NexusPayPrincipal pb = service.authenticate(RAW_B);
        assertThat(pb.userId()).as("B resolves to B regardless of candidate order").isEqualTo("key_B");
        assertThat(pb.live()).isFalse();
    }

    // (b) No oracle: wrong key on a populated colliding prefix is indistinguishable from a non-existent prefix.
    @Test
    void wrongKeyOnCollidingPrefix_isIndistinguishableFromNonexistentPrefix() {
        ApiKeyEntity a = entity(RAW_A, false, "key_A", "operator", "tenant_A");
        ApiKeyEntity b = entity(RAW_B, false, "key_B", "admin", "tenant_B");

        // Prefix EXISTS (2 candidates) but the supplied key matches NEITHER hash.
        ApiKeyService populated = serviceReturning(List.of(a, b));
        AuthorizationException onPopulated = (AuthorizationException) catchAuth(() -> populated.authenticate(RAW_WRONG));

        // Prefix does NOT exist (empty list).
        ApiKeyService empty = serviceReturning(List.of());
        AuthorizationException onEmpty = (AuthorizationException) catchAuth(() -> empty.authenticate(RAW_WRONG));

        AuthorizationException reference = AuthorizationException.invalidApiKey();

        // Same exception type, same errorCode, same message -> the filter renders a byte-identical 401.
        assertThat(onPopulated.getErrorCode()).isEqualTo("invalid_api_key");
        assertThat(onPopulated.getMessage()).isEqualTo(reference.getMessage());
        assertThat(onEmpty.getErrorCode()).isEqualTo("invalid_api_key");
        assertThat(onEmpty.getMessage()).isEqualTo(reference.getMessage());

        // The two outcomes must be indistinguishable (no candidate-count / prefix-existence oracle).
        assertThat(onPopulated.getErrorCode()).isEqualTo(onEmpty.getErrorCode());
        assertThat(onPopulated.getMessage()).isEqualTo(onEmpty.getMessage());
    }

    // (c) Revoked sibling excluded by the query; the un-revoked sibling still authenticates.
    @Test
    void revokedSiblingExcluded_unrevokedSiblingStillAuthenticates() {
        // Query semantics: findByKeyPrefixAndRevokedAtIsNull filters revoked rows, so only the
        // un-revoked sibling is ever returned. The revoked sibling's raw key is absent from the list.
        ApiKeyEntity live = entity(RAW_A, false, "key_live", "operator", "tenant_A");
        ApiKeyService service = serviceReturning(List.of(live)); // revoked sibling NOT present

        NexusPayPrincipal principal = service.authenticate(RAW_A);
        assertThat(principal).isNotNull();
        assertThat(principal.userId()).isEqualTo("key_live");

        // The revoked sibling (RAW_B) is excluded by the query -> uniform invalid_api_key.
        assertThatThrownBy(() -> service.authenticate(RAW_B))
                .isInstanceOf(AuthorizationException.class)
                .extracting(e -> ((AuthorizationException) e).getErrorCode())
                .isEqualTo("invalid_api_key");
    }

    // (c, persistence-layer lock for L-044) Derived-query CONTRACT: the {@code AndRevokedAtIsNull}
    // predicate is generated by Spring Data PURELY from the finder's method NAME. The service-layer
    // tests above stub the repository and so hand-feed an already-filtered list — they cannot fail if
    // someone keeps the call site but silently drops the revoked filter (e.g. renames to
    // {@code findByKeyPrefix}, or pins a hand-written {@code @Query} without the predicate). This repo
    // has no H2/@DataJpaTest persistence harness (every DB test is Testcontainers+Postgres behind an
    // {@code IntegrationTestBase}, Docker-gated and excluded from the unit gate), and the iam library
    // module ships no {@code @SpringBootConfiguration} anchor — so a true slice test is out of scope
    // here. Instead we lock the derivation INPUT at compile/gate time: the method must (1) be named
    // exactly {@code findByKeyPrefixAndRevokedAtIsNull} so the revoked predicate is parsed from the
    // name, (2) return a {@code List<ApiKeyEntity>} (invariant 1: >1 row never throws
    // IncorrectResultSize), and (3) carry NO {@code @Query} override that could substitute an
    // un-filtered SQL body under the same name. Dropping or altering {@code AndRevokedAtIsNull} fails
    // exactly one of these.
    @Test
    void finderMethodName_encodesRevokedExclusion_andReturnsList_withNoQueryOverride() throws NoSuchMethodException {
        Method finder = JpaApiKeyRepository.class.getMethod(
                "findByKeyPrefixAndRevokedAtIsNull", String.class);

        // (1) The name carries the predicate. Spring Data derives "... AND revoked_at IS NULL"
        // solely from "AndRevokedAtIsNull"; if that suffix is dropped the predicate is gone.
        assertThat(finder.getName())
                .as("revoked-exclusion predicate is derived from the method name")
                .endsWith("AndRevokedAtIsNull");

        // (2) Returns a List of ApiKeyEntity -> a colliding (>1 row) prefix yields a list, never an
        // IncorrectResultSizeDataAccessException (the reverted Optional finder's failure mode).
        assertThat(finder.getReturnType()).isEqualTo(List.class);
        var elementType = ((ParameterizedType) finder.getGenericReturnType())
                .getActualTypeArguments()[0];
        assertThat(elementType).isEqualTo(ApiKeyEntity.class);

        // (3) No @Query override: the predicate stays name-derived, so it cannot be silently swapped
        // for an un-filtered SQL body while keeping the method name (the name-preserving bypass the
        // service-layer stubs are blind to).
        assertThat(finder.isAnnotationPresent(Query.class))
                .as("finder must stay a derived query so AndRevokedAtIsNull cannot be bypassed by a hand-written @Query")
                .isFalse();
    }

    // (c, cont. — service-layer multi-row lock) Two UN-REVOKED siblings on the colliding prefix BOTH
    // present in the finder result: each must authenticate to its OWN principal. Unlike the stubbed
    // single-row case above, this exercises the candidate-iteration over a genuine >1-element list at
    // the service layer (invariant 1), independent of any persistence-layer filtering.
    @Test
    void twoUnrevokedSiblingsOnPrefix_eachStillResolvesToOwnPrincipal() {
        ApiKeyEntity a = entity(RAW_A, false, "key_A", "operator", "tenant_A");
        ApiKeyEntity b = entity(RAW_B, false, "key_B", "admin", "tenant_B");

        ApiKeyService service = serviceReturning(List.of(a, b)); // both un-revoked, same prefix

        assertThat(service.authenticate(RAW_A).userId()).isEqualTo("key_A");
        assertThat(service.authenticate(RAW_B).userId()).isEqualTo("key_B");

        // A revoked sibling never reaches this list (excluded by the query) -> wrong/absent key 401s
        // with the same uniform code, no candidate-count oracle.
        assertThatThrownBy(() -> service.authenticate(RAW_WRONG))
                .isInstanceOf(AuthorizationException.class)
                .extracting(e -> ((AuthorizationException) e).getErrorCode())
                .isEqualTo("invalid_api_key");
    }

    // Edge guard: non-sk_ raw key -> OIDC fallthrough (null), finder never touched.
    @Test
    void nonApiKey_returnsNull_andNeverHitsRepository() {
        JpaApiKeyRepository repo = mock(JpaApiKeyRepository.class);
        ApiKeyService service = new ApiKeyService(repo, mock(ApiKeyUsageTracker.class));

        assertThat(service.authenticate("Bearer-ish jwt")).isNull();
        verifyNoInteractions(repo);
    }

    // Edge guard: null raw key -> null (no NPE), finder never called.
    @Test
    void nullRawKey_returnsNull_noNpe() {
        JpaApiKeyRepository repo = mock(JpaApiKeyRepository.class);
        ApiKeyService service = new ApiKeyService(repo, mock(ApiKeyUsageTracker.class));

        assertThat(service.authenticate(null)).isNull();
        verify(repo, never()).findByKeyPrefixAndRevokedAtIsNull(anyString());
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
