package io.nexuspay.gateway.adapter.in.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-014: {@link IdempotencyFilter} is the double-charge guard. Three load-bearing behaviors:
 * (a) the Redis key is scoped per caller (no cross-tenant cache leak), (b) 5xx releases the lock
 * (no caching transient failures) while 2xx/4xx are cached, and (c) a missed lock serves the cached
 * response instead of re-processing. All verified against a mocked StringRedisTemplate.
 */
class IdempotencyFilterTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ObjectMapper objectMapper;
    private IdempotencyFilter filter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        objectMapper = new ObjectMapper();
        filter = new IdempotencyFilter(redis, objectMapper);
    }

    private MockHttpServletRequest post(String key, String auth) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/payments");
        if (key != null) req.addHeader("Idempotency-Key", key);
        if (auth != null) req.addHeader("Authorization", auth);
        return req;
    }

    /** A chain that simulates a downstream handler producing the given status + body. */
    private static FilterChain handler(int status, String body) {
        return (request, response) -> {
            HttpServletResponse resp = (HttpServletResponse) response;
            resp.setStatus(status);
            resp.setContentType("application/json");
            resp.getWriter().write(body);
        };
    }

    // ---- shouldNotFilter ----

    @Test
    void shouldNotFilter_trueForNonPost_falseForPost() {
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/v1/payments"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("PUT", "/v1/payments"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/v1/payments"))).isFalse();
    }

    // ---- missing key ----

    @Test
    void missingKey_proceedsWithoutRedis() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilterInternal(post(null, "Bearer k"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).as("chain must have run").isNotNull();
        verify(redis, never()).opsForValue();
    }

    @Test
    void blankKey_proceedsWithoutRedis() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/payments");
        req.addHeader("Idempotency-Key", "   ");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(req, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(redis, never()).opsForValue();
    }

    // ---- caller scope isolation ----

    @Test
    @SuppressWarnings("unchecked")
    void sameKeyDifferentCallers_useDifferentRedisKeys() throws Exception {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        filter.doFilterInternal(post("order-1", "Bearer merchantA"),
                new MockHttpServletResponse(), handler(200, "{}"));
        filter.doFilterInternal(post("order-1", "Bearer merchantB"),
                new MockHttpServletResponse(), handler(200, "{}"));

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(valueOps, times(2)).setIfAbsent(keys.capture(), anyString(), any(Duration.class));

        String keyA = keys.getAllValues().get(0);
        String keyB = keys.getAllValues().get(1);
        assertThat(keyA)
                .as("two merchants reusing order-1 must NOT collide")
                .isNotEqualTo(keyB);
        // Scope is a 16-hex (8-byte) SHA-256 prefix; full key is idempotency:<scope>:order-1
        assertThat(keyA).matches("idempotency:[0-9a-f]{16}:order-1");
        assertThat(keyB).matches("idempotency:[0-9a-f]{16}:order-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void noAuthHeader_usesAnonScope() throws Exception {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        filter.doFilterInternal(post("order-1", null), new MockHttpServletResponse(), handler(200, "{}"));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(valueOps).setIfAbsent(key.capture(), anyString(), any(Duration.class));
        assertThat(key.getValue()).isEqualTo("idempotency:anon:order-1");
    }

    // ---- cache on success, release on 5xx ----

    @Test
    @SuppressWarnings("unchecked")
    void lockAcquired_success200_cachesResponseFor24h() throws Exception {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilterInternal(post("order-1", "Bearer k"), resp, handler(200, "{\"id\":\"pi_1\"}"));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("idempotency:" + scope("Bearer k") + ":order-1"),
                json.capture(), eq(Duration.ofHours(24)));
        verify(redis, never()).delete(anyString());

        // The cached entry is the serialized CachedResponse with status + body.
        var cached = objectMapper.readValue(json.getValue(), IdempotencyFilter.CachedResponse.class);
        assertThat(cached.status()).isEqualTo(200);
        assertThat(cached.body()).isEqualTo("{\"id\":\"pi_1\"}");
        // The downstream body still reaches the client.
        assertThat(resp.getContentAsString()).isEqualTo("{\"id\":\"pi_1\"}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void lockAcquired_clientError400_isCached() throws Exception {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        filter.doFilterInternal(post("order-1", "Bearer k"),
                new MockHttpServletResponse(), handler(400, "{\"error\":\"bad\"}"));

        verify(valueOps).set(anyString(), anyString(), eq(Duration.ofHours(24)));
        verify(redis, never()).delete(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void lockAcquired_serverError500_releasesLockAndDoesNotCache() throws Exception {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        filter.doFilterInternal(post("order-1", "Bearer k"),
                new MockHttpServletResponse(), handler(500, "{\"error\":\"boom\"}"));

        // Transient failure must NOT be pinned for 24h — lock deleted, nothing cached.
        verify(redis).delete("idempotency:" + scope("Bearer k") + ":order-1");
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    // ---- serve cached when lock not acquired ----

    @Test
    @SuppressWarnings("unchecked")
    void lockNotAcquired_servesCachedResponse_andSkipsChain() throws Exception {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        String cachedJson = objectMapper.writeValueAsString(
                new IdempotencyFilter.CachedResponse(201, "application/json", "{\"id\":\"pi_cached\"}"));
        when(valueOps.get(anyString())).thenReturn(cachedJson);

        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(post("order-1", "Bearer k"), resp, chain);

        assertThat(resp.getStatus()).isEqualTo(201);
        assertThat(resp.getContentType()).isEqualTo("application/json");
        assertThat(resp.getContentAsString()).isEqualTo("{\"id\":\"pi_cached\"}");
        verify(chain, never()).doFilter(any(), any());
    }

    // ---- fail open ----

    @Test
    @SuppressWarnings("unchecked")
    void redisThrows_failsOpenAndProceeds() throws Exception {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("valkey down"));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(post("order-1", "Bearer k"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).as("must still process when Redis is unavailable").isNotNull();
    }

    /** Mirror of the filter's caller-scope hash: first 8 bytes of SHA-256(auth), hex. */
    private static String scope(String auth) throws Exception {
        var sha = java.security.MessageDigest.getInstance("SHA-256");
        byte[] d = sha.digest(auth.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(d, 0, 8);
    }
}
