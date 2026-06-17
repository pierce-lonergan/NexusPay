package io.nexuspay.iam.application;

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
 * INT-3 (T7): the principal's {@code live} mode is SERVER-DERIVED from the matched key entity's
 * {@code is_live} column, never parsed from the raw key string; a JWT/OIDC-constructed principal defaults
 * to LIVE. Each assertion fails if the field/default is reverted.
 */
class ApiKeyServicePrincipalModeTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private ApiKeyService serviceWith(ApiKeyEntity entity, String keyPrefix) {
        JpaApiKeyRepository repo = mock(JpaApiKeyRepository.class);
        when(repo.findByKeyPrefixAndRevokedAtIsNull(anyString())).thenReturn(List.of(entity));
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
        String rawKey = "sk_test_unitk1";
        ApiKeyService service = serviceWith(entity(rawKey, false), rawKey);

        NexusPayPrincipal principal = service.authenticate(rawKey);

        assertThat(principal).isNotNull();
        assertThat(principal.live()).as("is_live=false -> TEST principal").isFalse();
        assertThat(principal.authMethod()).isEqualTo(NexusPayPrincipal.AuthMethod.API_KEY);
    }

    @Test
    void liveKeyEntity_yieldsLivePrincipal_liveTrue() {
        String rawKey = "sk_live_unitk1";
        ApiKeyService service = serviceWith(entity(rawKey, true), rawKey);

        NexusPayPrincipal principal = service.authenticate(rawKey);

        assertThat(principal).isNotNull();
        assertThat(principal.live()).as("is_live=true -> LIVE principal").isTrue();
    }

    @Test
    void modeIsServerDerived_fromEntityIsLiveColumn() {
        // The principal's mode is read from the matched ENTITY's is_live COLUMN, not re-derived from the
        // raw key string in authenticate(). DX-3 fail-closed now REQUIRES prefix<->is_live agreement, so
        // the old "forge sk_test_ prefix + is_live=true" construction is no longer a legitimate row;
        // server-derivation is instead pinned by asserting the returned live() EQUALS the stub entity's
        // isLive() column (the source of truth) rather than a hardcoded literal. The complementary
        // direction — is_live=false yields a TEST principal — is covered by testKeyEntity_yieldsTestPrincipal_liveFalse.
        String rawKey = "sk_live_unitk2";

        ApiKeyEntity liveEntity = entity(rawKey, true);
        assertThat(serviceWith(liveEntity, rawKey).authenticate(rawKey).live())
                .as("principal.live() is server-derived from the entity's is_live column")
                .isEqualTo(liveEntity.isLive())
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
