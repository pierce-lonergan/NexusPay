package io.nexuspay.iam.application;

import io.nexuspay.iam.adapter.out.persistence.ApiKeyEntity;
import io.nexuspay.iam.adapter.out.persistence.JpaApiKeyRepository;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * INT-3 (T7): the principal's {@code live} mode is SERVER-DERIVED from the matched key entity's
 * {@code is_live} column, never parsed from the raw key string; a JWT/OIDC-constructed principal defaults
 * to LIVE. Each assertion fails if the field/default is reverted.
 */
class ApiKeyServicePrincipalModeTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private ApiKeyService serviceWith(ApiKeyEntity entity, String keyPrefix) {
        JpaApiKeyRepository repo = mock(JpaApiKeyRepository.class);
        when(repo.findByKeyPrefixAndRevokedAtIsNull(anyString())).thenReturn(Optional.of(entity));
        return new ApiKeyService(repo);
    }

    private ApiKeyEntity entity(String rawKey, boolean live) {
        String prefix = rawKey.substring(0, Math.min(rawKey.length(), 12));
        return new ApiKeyEntity("key_1", encoder.encode(rawKey), prefix, "name", "operator",
                "tenant_1", live, Instant.now(), null);
    }

    @Test
    void testKeyEntity_yieldsTestPrincipal_liveFalse() {
        // A real sk_test_ key whose entity is_live=false -> principal.live()==false.
        String rawKey = "sk_test_abcdef0123456789";
        ApiKeyService service = serviceWith(entity(rawKey, false), rawKey);

        NexusPayPrincipal principal = service.authenticate(rawKey);

        assertThat(principal).isNotNull();
        assertThat(principal.live()).as("is_live=false -> TEST principal").isFalse();
        assertThat(principal.authMethod()).isEqualTo(NexusPayPrincipal.AuthMethod.API_KEY);
    }

    @Test
    void liveKeyEntity_yieldsLivePrincipal_liveTrue() {
        String rawKey = "sk_live_abcdef0123456789";
        ApiKeyService service = serviceWith(entity(rawKey, true), rawKey);

        NexusPayPrincipal principal = service.authenticate(rawKey);

        assertThat(principal).isNotNull();
        assertThat(principal.live()).as("is_live=true -> LIVE principal").isTrue();
    }

    @Test
    void modeIsServerDerived_notFromRawKeyString() {
        // A key whose RAW STRING says sk_test_ but whose ENTITY is_live=true must yield LIVE — the
        // mode comes from the column, never from parsing the string. (Defensive: in production the prefix
        // and is_live agree, but this proves the source of truth.)
        String rawKey = "sk_test_serverDerivedProof";
        ApiKeyService service = serviceWith(entity(rawKey, true), rawKey);

        assertThat(service.authenticate(rawKey).live())
                .as("mode is the entity's is_live, not the raw key prefix")
                .isTrue();
    }

    @Test
    void jwtOidcPrincipal_defaultsToLive() {
        // The JWT/OIDC path builds a NexusPayPrincipal via the 4-arg constructor -> live defaults true.
        NexusPayPrincipal jwt = new NexusPayPrincipal("u", "t", "admin",
                NexusPayPrincipal.AuthMethod.JWT);
        assertThat(jwt.live()).as("a console/OIDC actor is LIVE by default").isTrue();

        // The session-token 5-arg constructor also defaults to LIVE.
        NexusPayPrincipal session = new NexusPayPrincipal("u", "t", "viewer",
                NexusPayPrincipal.AuthMethod.SESSION_TOKEN, "sess_1");
        assertThat(session.live()).isTrue();
    }
}
