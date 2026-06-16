package io.nexuspay.gateway.adapter.in.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-014: {@link RateLimitFilter} is the abuse/DoS control on the money API. It must emit 429 +
 * Retry-After + X-RateLimit-* headers when the bucket is empty, FAIL OPEN when Valkey is down
 * (availability over strict limiting), skip infra paths, and only act on authenticated principals.
 */
class RateLimitFilterTest {

    private StringRedisTemplate redis;
    private ObjectMapper objectMapper;
    private RateLimitFilter filter;

    private static final int MAX = 100;
    private static final int WINDOW = 60;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        objectMapper = new ObjectMapper();
        filter = new RateLimitFilter(redis, objectMapper, MAX, WINDOW);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String name) {
        var auth = new UsernamePasswordAuthenticationToken(name, "creds", List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private MockHttpServletRequest req(String uri) {
        return new MockHttpServletRequest("POST", uri);
    }

    // ---- shouldNotFilter ----

    @Test
    void shouldNotFilter_skipsInfraPaths_butFiltersApi() {
        assertThat(filter.shouldNotFilter(req("/actuator/health"))).isTrue();
        assertThat(filter.shouldNotFilter(req("/internal/foo"))).isTrue();
        assertThat(filter.shouldNotFilter(req("/v1/api-docs"))).isTrue();
        assertThat(filter.shouldNotFilter(req("/v1/swagger-ui/index.html"))).isTrue();
        assertThat(filter.shouldNotFilter(req("/v1/payments"))).isFalse();
    }

    // ---- unauthenticated ----

    @Test
    void noPrincipal_proceedsWithoutCallingRedis() throws Exception {
        // No SecurityContext authentication set.
        MockFilterChain chain = new MockFilterChain();
        filter.doFilterInternal(req("/v1/payments"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void notAuthenticated_proceedsWithoutCallingRedis() throws Exception {
        var token = new TestingAuthenticationToken("user", "creds"); // isAuthenticated() == false
        SecurityContextHolder.getContext().setAuthentication(token);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(req("/v1/payments"), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any());
    }

    // ---- allowed ----

    @Test
    @SuppressWarnings("unchecked")
    void allowed_setsRateLimitHeaders_andProceeds() throws Exception {
        authenticate("key_abc");
        // RateLimitFilter calls execute(script, keys, maxRequests, windowSeconds, now) — THREE varargs.
        // A single any() only matches a one-element varargs call, so it would never match and execute()
        // would return null (filter falls through, no headers set). Match all three vararg elements.
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenReturn(List.of(1L, 42L, 0L));
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilterInternal(req("/v1/payments"), resp, chain);

        assertThat(resp.getHeader("X-RateLimit-Limit")).isEqualTo(String.valueOf(MAX));
        assertThat(resp.getHeader("X-RateLimit-Remaining")).isEqualTo("42");
        assertThat(resp.getStatus()).isNotEqualTo(429);
        assertThat(chain.getRequest()).as("allowed request must proceed").isNotNull();
    }

    // ---- blocked ----

    @Test
    @SuppressWarnings("unchecked")
    void blocked_returns429_withRetryAfter_andJsonBody_andDoesNotProceed() throws Exception {
        authenticate("key_abc");
        // Match all three vararg elements (maxRequests, windowSeconds, now) — see allowed_ test.
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenReturn(List.of(0L, 0L, 30L));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilterInternal(req("/v1/payments"), resp, chain);

        assertThat(resp.getStatus()).isEqualTo(429);
        assertThat(resp.getHeader("Retry-After")).isEqualTo("30");
        assertThat(resp.getContentType()).contains("application/json");

        JsonNode body = objectMapper.readTree(resp.getContentAsString());
        assertThat(body.path("error").path("type").asText()).isEqualTo("rate_limit_error");
        assertThat(body.path("error").path("code").asText()).isEqualTo("rate_limit_exceeded");
        // INT-2: the 429 envelope now carries a request_id (from the correlation MDC, with a UUID
        // fallback so it is never blank). FAILS if the request_id wiring is reverted.
        assertThat(body.path("error").path("request_id").asText()).isNotBlank();

        verify(chain, never()).doFilter(any(), any());
    }

    // ---- fail open ----

    @Test
    @SuppressWarnings("unchecked")
    void redisThrows_failsOpen_proceedsWithout429() throws Exception {
        authenticate("key_abc");
        // Match all three vararg elements so the throw actually fires on the real call shape.
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenThrow(new RuntimeException("valkey unreachable"));
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilterInternal(req("/v1/payments"), resp, chain);

        assertThat(resp.getStatus()).isNotEqualTo(429);
        assertThat(chain.getRequest()).as("must fail open when Valkey is down").isNotNull();
    }
}
