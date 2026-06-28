package io.nexuspay.gateway.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GAP-079 (critique v3 F6): pins that {@link IdempotencyScope} reproduces the EXACT caller scope the
 * {@code IdempotencyFilter} has always used — the first 8 bytes of {@code SHA-256(Authorization)} as hex,
 * {@code "anon"} when no Authorization header. This is the anti-drift guard: the filter (writer) and the
 * inspect/clear controller (reader) MUST derive the same scope, or a caller would inspect the wrong keys.
 */
class IdempotencyScopeTest {

    private static MockHttpServletRequest req(String auth) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/v1/payments");
        if (auth != null) r.addHeader("Authorization", auth);
        return r;
    }

    /** Independent re-derivation of the historical filter hash (matches IdempotencyFilterTest.scope). */
    private static String expectedScope(String auth) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] d = sha.digest(auth.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(d, 0, 8);
    }

    @Test
    void forRequest_isFirst8BytesSha256HexOfAuthorization() throws Exception {
        String scope = IdempotencyScope.forRequest(req("Bearer k"));
        assertThat(scope).isEqualTo(expectedScope("Bearer k"));
        assertThat(scope).matches("[0-9a-f]{16}");
    }

    @Test
    void keyPrefix_isIdempotencyScopeColon() throws Exception {
        String prefix = IdempotencyScope.keyPrefix(req("Bearer k"));
        assertThat(prefix).isEqualTo("idempotency:" + expectedScope("Bearer k") + ":");
    }

    @Test
    void noAuthHeader_yieldsAnonScope() {
        assertThat(IdempotencyScope.forRequest(req(null))).isEqualTo("anon");
        assertThat(IdempotencyScope.keyPrefix(req(null))).isEqualTo("idempotency:anon:");
    }

    @Test
    void blankAuthHeader_yieldsAnonScope() {
        assertThat(IdempotencyScope.forRequest(req("   "))).isEqualTo("anon");
    }

    @Test
    void differentAuth_yieldsDifferentScope() {
        assertThat(IdempotencyScope.forRequest(req("Bearer A")))
                .isNotEqualTo(IdempotencyScope.forRequest(req("Bearer B")));
    }
}
