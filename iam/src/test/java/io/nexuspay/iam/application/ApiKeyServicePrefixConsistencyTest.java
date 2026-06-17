package io.nexuspay.iam.application;

import io.nexuspay.common.exception.AuthorizationException;
import io.nexuspay.iam.adapter.out.persistence.ApiKeyEntity;
import io.nexuspay.iam.adapter.out.persistence.JpaApiKeyRepository;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Critique 3.2 regression proof: the {@code sk_test_}/{@code sk_live_} prefix is a REAL, fail-closed
 * preventive control, not a cosmetic label. {@code authenticate} now verifies — AFTER a bcrypt hash match
 * — that the stored {@code key_prefix}'s mode AGREES with the stored {@code is_live} flag
 * ({@code sk_live_} REQUIRES {@code is_live=true}; {@code sk_test_} REQUIRES {@code is_live=false}). A row
 * whose prefix/is_live disagree (manual DB edit, a future code path, corruption) is REJECTED exactly like
 * a non-match: the same {@link AuthorizationException#invalidApiKey()}, no distinguishing oracle.
 *
 * <p>Reverting the check (trusting {@code is_live} alone again) flips the two mismatch cases from REJECTED
 * to authenticated-with-the-wrong-mode, failing those tests. The consistent cases pin that every existing
 * valid key (sk_live_+true, sk_test_+false) still authenticates unchanged.
 */
class ApiKeyServicePrefixConsistencyTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private static final String RAW_LIVE = "sk_live_consistent_x";
    private static final String RAW_TEST = "sk_test_consistent_x";

    /**
     * Builds an entity whose stored hash matches {@code rawKey} but whose stored {@code key_prefix} and
     * {@code is_live} are set INDEPENDENTLY — letting us forge a mismatched row the boundary check must
     * reject. (In production createApiKey keeps them consistent; this simulates corruption / a future
     * mismatched writer.)
     */
    private ApiKeyEntity entity(String rawKey, String storedPrefix, boolean isLive, String id) {
        return new ApiKeyEntity(id, encoder.encode(rawKey), storedPrefix, "name", "operator",
                "tenant_A", isLive, Instant.now(), null);
    }

    private ApiKeyService serviceReturning(List<ApiKeyEntity> candidates) {
        JpaApiKeyRepository repo = mock(JpaApiKeyRepository.class);
        when(repo.findByKeyPrefixAndRevokedAtIsNull(anyString())).thenReturn(candidates);
        return new ApiKeyService(repo, mock(ApiKeyUsageTracker.class));
    }

    // --- Consistent keys authenticate normally (existing valid keys preserved) ---

    @Test
    void consistentLiveKey_authenticatesAsLive() {
        ApiKeyEntity live = entity(RAW_LIVE, "sk_live_cons", true, "key_live");
        ApiKeyService service = serviceReturning(List.of(live));

        NexusPayPrincipal principal = service.authenticate(RAW_LIVE);
        assertThat(principal).isNotNull();
        assertThat(principal.userId()).isEqualTo("key_live");
        assertThat(principal.live()).as("sk_live_ + is_live=true -> LIVE principal").isTrue();
    }

    @Test
    void consistentTestKey_authenticatesAsTest() {
        ApiKeyEntity test = entity(RAW_TEST, "sk_test_cons", false, "key_test");
        ApiKeyService service = serviceReturning(List.of(test));

        NexusPayPrincipal principal = service.authenticate(RAW_TEST);
        assertThat(principal).isNotNull();
        assertThat(principal.userId()).isEqualTo("key_test");
        assertThat(principal.live()).as("sk_test_ + is_live=false -> TEST principal").isFalse();
    }

    // --- Mismatched keys are REJECTED (fail-closed), even though the hash matches ---

    @Test
    void testPrefix_butIsLiveTrue_isRejected() {
        // Forged row: stored prefix says "test" but is_live=true. Hash matches the raw key.
        ApiKeyEntity forged = entity(RAW_TEST, "sk_test_cons", true, "key_forged_1");
        ApiKeyService service = serviceReturning(List.of(forged));

        assertThat(catchAuth(() -> service.authenticate(RAW_TEST)))
                .as("matched-but-inconsistent key must be rejected (not authenticated)")
                .isInstanceOf(AuthorizationException.class);
    }

    @Test
    void livePrefix_butIsLiveFalse_isRejected() {
        // Forged row: stored prefix says "live" but is_live=false. Hash matches the raw key.
        ApiKeyEntity forged = entity(RAW_LIVE, "sk_live_cons", false, "key_forged_2");
        ApiKeyService service = serviceReturning(List.of(forged));

        assertThat(catchAuth(() -> service.authenticate(RAW_LIVE)))
                .isInstanceOf(AuthorizationException.class);
    }

    // --- No oracle: a mismatched (matched) key fails IDENTICALLY to an unknown/non-matching key ---

    @Test
    void mismatch_isIndistinguishableFromUnknownKey() {
        ApiKeyEntity forged = entity(RAW_LIVE, "sk_live_cons", false, "key_forged_2");
        ApiKeyService onForged = serviceReturning(List.of(forged));
        AuthorizationException onMismatch = (AuthorizationException) catchAuth(() -> onForged.authenticate(RAW_LIVE));

        // An empty candidate list (prefix does not exist at all).
        ApiKeyService empty = serviceReturning(List.of());
        AuthorizationException onUnknown = (AuthorizationException) catchAuth(() -> empty.authenticate(RAW_LIVE));

        AuthorizationException reference = AuthorizationException.invalidApiKey();

        // Same code + message as the canonical invalid-key failure -> the filter renders an identical 401.
        assertThat(onMismatch.getErrorCode()).isEqualTo("invalid_api_key");
        assertThat(onMismatch.getMessage()).isEqualTo(reference.getMessage());

        // And byte-identical to the unknown-key outcome: no prefix-mismatch oracle distinguishes them.
        assertThat(onMismatch.getErrorCode()).isEqualTo(onUnknown.getErrorCode());
        assertThat(onMismatch.getMessage()).isEqualTo(onUnknown.getMessage());
    }

    // --- A valid sibling on the same colliding prefix list still authenticates; the forged sibling does not ---

    @Test
    void forgedSiblingRejected_validSiblingStillAuthenticates() {
        // Both raw keys share the leading prefix; the finder (mocked) returns both candidates.
        ApiKeyEntity valid = entity(RAW_LIVE, "sk_live_cons", true, "key_valid");
        ApiKeyEntity forged = entity(RAW_TEST, "sk_test_cons", true, "key_forged"); // test prefix + is_live=true
        ApiKeyService service = serviceReturning(List.of(forged, valid));

        // The valid live key authenticates (the forged sibling never matches its hash anyway).
        NexusPayPrincipal principal = service.authenticate(RAW_LIVE);
        assertThat(principal.userId()).isEqualTo("key_valid");
        assertThat(principal.live()).isTrue();

        // The forged key's raw value matches only the forged (inconsistent) row -> rejected.
        assertThat(catchAuth(() -> service.authenticate(RAW_TEST)))
                .isInstanceOf(AuthorizationException.class);
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
