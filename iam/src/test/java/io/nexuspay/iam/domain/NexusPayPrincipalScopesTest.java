package io.nexuspay.iam.domain;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DX-5c-ii: {@link NexusPayPrincipal} scope semantics. The DEFAULT (convenience-constructor) principal —
 * the JWT / session / pre-DX-5c-ii path — is UNRESTRICTED and byte-identical to before (L-062); only a
 * principal explicitly built with a non-empty scope set is RESTRICTED.
 */
class NexusPayPrincipalScopesTest {

    @Test
    void fourArgConstructor_defaultsToUnrestricted_backCompat() {
        // The JWT/OIDC path. scopes defaults to null == unrestricted; hasScope is true for everything.
        NexusPayPrincipal jwt = new NexusPayPrincipal("u", "t", "admin",
                NexusPayPrincipal.AuthMethod.JWT);
        assertThat(jwt.scopes()).isNull();
        assertThat(jwt.hasScope("payments:write")).isTrue();
        assertThat(jwt.hasScope("vault:write")).isTrue();
    }

    @Test
    void fiveArgConstructor_defaultsToUnrestricted_backCompat() {
        NexusPayPrincipal session = new NexusPayPrincipal("u", "t", "viewer",
                NexusPayPrincipal.AuthMethod.SESSION_TOKEN, "sess_1");
        assertThat(session.scopes()).isNull();
        assertThat(session.hasScope("disputes:read")).isTrue();
    }

    @Test
    void sixArgConstructor_defaultsToUnrestricted_backCompat() {
        // The pre-DX-5c-ii API-key construction shape ( ..., sessionId, live ).
        NexusPayPrincipal apiKey = new NexusPayPrincipal("key_1", "t", "operator",
                NexusPayPrincipal.AuthMethod.API_KEY, null, false);
        assertThat(apiKey.scopes()).isNull();
        assertThat(apiKey.live()).isFalse();
        assertThat(apiKey.hasScope("payments:read")).isTrue();
    }

    @Test
    void emptyScopeSet_isUnrestricted_footGunAvoided() {
        NexusPayPrincipal empty = new NexusPayPrincipal("key_1", "t", "operator",
                NexusPayPrincipal.AuthMethod.API_KEY, null, false, Set.of());
        assertThat(empty.hasScope("payments:write")).as("empty == unrestricted, not locked out").isTrue();
    }

    @Test
    void nonEmptyScopeSet_isRestricted_failClosedForUngranted() {
        NexusPayPrincipal restricted = new NexusPayPrincipal("key_1", "t", "operator",
                NexusPayPrincipal.AuthMethod.API_KEY, null, false, Set.of("payments:read"));
        assertThat(restricted.hasScope("payments:read")).isTrue();
        assertThat(restricted.hasScope("payments:write")).isFalse();
        assertThat(restricted.hasScope("refunds:read")).isFalse();
    }
}
