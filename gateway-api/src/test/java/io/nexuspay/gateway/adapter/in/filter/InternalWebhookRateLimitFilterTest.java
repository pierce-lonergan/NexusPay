package io.nexuspay.gateway.adapter.in.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SEC-21: {@link InternalWebhookRateLimitFilter} is the per-IP DoS / HMAC-brute-force control on the
 * unauthenticated inbound webhook endpoints under {@code /internal/webhooks/**}. It must: filter ONLY
 * those paths; emit 429 + Retry-After + X-RateLimit-* + the common ApiError envelope when the bucket
 * is empty; FAIL OPEN when Valkey is down (real PSP events must not be dropped); and resolve the client
 * IP spoof-resistantly (remoteAddr by default; RIGHTMOST X-Forwarded-For entry only when explicitly
 * trusting a single LB).
 */
class InternalWebhookRateLimitFilterTest {

    private static final int MAX = 60;
    private static final int WINDOW = 60;

    private InternalWebhookRateLimitFilter filter(StringRedisTemplate redis, ObjectMapper om, boolean trustXff) {
        return new InternalWebhookRateLimitFilter(redis, om, MAX, WINDOW, trustXff);
    }

    private MockHttpServletRequest req(String uri) {
        var r = new MockHttpServletRequest("POST", uri);
        r.setRemoteAddr("10.0.0.5");
        return r;
    }

    // ---- shouldNotFilter ----

    @Test
    void shouldNotFilter_onlyInternalWebhookPaths() {
        var f = filter(mock(StringRedisTemplate.class), new ObjectMapper(), false);
        // Filtered (returns false from shouldNotFilter):
        assertThat(f.shouldNotFilter(req("/internal/webhooks/hyperswitch"))).isFalse();
        assertThat(f.shouldNotFilter(req("/internal/webhooks/disputes"))).isFalse();
        // Skipped (true): the rest of the API, including other /internal/ paths.
        assertThat(f.shouldNotFilter(req("/internal/foo"))).isTrue();
        assertThat(f.shouldNotFilter(req("/v1/payments"))).isTrue();
        assertThat(f.shouldNotFilter(req("/actuator/health"))).isTrue();
    }

    // ---- allowed (within limit) ----

    @Test
    @SuppressWarnings("unchecked")
    void withinLimit_setsHeaders_andProceeds() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(List.of(1L, 41L, 0L));
        var f = filter(redis, new ObjectMapper(), false);
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        f.doFilterInternal(req("/internal/webhooks/hyperswitch"), resp, chain);

        assertThat(resp.getHeader("X-RateLimit-Limit")).isEqualTo(String.valueOf(MAX));
        assertThat(resp.getHeader("X-RateLimit-Remaining")).isEqualTo("41");
        assertThat(resp.getStatus()).isNotEqualTo(429);
        assertThat(chain.getRequest()).as("allowed request must proceed").isNotNull();
    }

    // ---- blocked (over limit) ----

    @Test
    @SuppressWarnings("unchecked")
    void overLimit_returns429_withRetryAfter_andEnvelope_andDoesNotProceed() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(List.of(0L, 0L, 1L));
        ObjectMapper om = new ObjectMapper();
        var f = filter(redis, om, false);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        f.doFilterInternal(req("/internal/webhooks/disputes"), resp, chain);

        assertThat(resp.getStatus()).isEqualTo(429);
        assertThat(resp.getHeader("Retry-After")).isEqualTo("1");
        assertThat(resp.getContentType()).contains("application/json");

        JsonNode body = om.readTree(resp.getContentAsString());
        assertThat(body.path("error").path("type").asText()).isEqualTo("rate_limit_error");
        assertThat(body.path("error").path("code").asText()).isEqualTo("rate_limit_exceeded");
        assertThat(body.path("error").path("request_id").asText()).isNotBlank();

        verify(chain, never()).doFilter(any(), any());
    }

    // ---- fail open ----

    @Test
    @SuppressWarnings("unchecked")
    void redisThrows_failsOpen_proceedsWithout429() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenThrow(new RuntimeException("valkey unreachable"));
        var f = filter(redis, new ObjectMapper(), false);
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        f.doFilterInternal(req("/internal/webhooks/hyperswitch"), resp, chain);

        assertThat(resp.getStatus()).isNotEqualTo(429);
        assertThat(chain.getRequest()).as("webhook limiter must fail OPEN when Valkey is down").isNotNull();
    }

    // ---- client IP extraction ----

    @Test
    @SuppressWarnings("unchecked")
    void ipExtraction_trustForwardedForFalse_usesRemoteAddr_ignoresXff() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(List.of(1L, 59L, 0L));
        var f = filter(redis, new ObjectMapper(), false); // trust-forwarded-for = false (default)

        MockHttpServletRequest request = req("/internal/webhooks/hyperswitch");
        request.addHeader("X-Forwarded-For", "1.2.3.4"); // attacker-supplied — must be IGNORED
        f.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());

        // Key is built from remoteAddr (10.0.0.5), NOT the spoofed XFF.
        verify(redis).execute(any(RedisScript.class), eq(List.of("ratelimit:internal-webhook:ip:10.0.0.5")),
                any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void ipExtraction_trustForwardedForTrue_usesRightmostXffEntry() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(List.of(1L, 59L, 0L));
        var f = filter(redis, new ObjectMapper(), true); // trust-forwarded-for = true (one trusted LB)

        MockHttpServletRequest request = req("/internal/webhooks/hyperswitch");
        // Leftmost entries are client-spoofable; the RIGHTMOST (203.0.113.9) is the IP the trusted LB
        // appended = the real client.
        request.addHeader("X-Forwarded-For", "1.2.3.4, 9.9.9.9, 203.0.113.9");
        f.doFilterInternal(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(redis).execute(any(RedisScript.class), eq(List.of("ratelimit:internal-webhook:ip:203.0.113.9")),
                any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void ipExtraction_spoofedLeftmostEntry_doesNotChangeKey() throws Exception {
        // Two requests with DIFFERENT spoofed leftmost entries but the SAME real (rightmost) client IP
        // must map to the SAME bucket key — proving leftmost spoofing cannot evade or pollute the limit.
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(List.of(1L, 59L, 0L));
        var f = filter(redis, new ObjectMapper(), true);

        MockHttpServletRequest r1 = req("/internal/webhooks/hyperswitch");
        r1.addHeader("X-Forwarded-For", "1.1.1.1, 203.0.113.9");
        f.doFilterInternal(r1, new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletRequest r2 = req("/internal/webhooks/hyperswitch");
        r2.addHeader("X-Forwarded-For", "2.2.2.2, 203.0.113.9");
        f.doFilterInternal(r2, new MockHttpServletResponse(), new MockFilterChain());

        // Both resolve to the SAME rightmost client IP -> SAME key (spoofed leftmost is irrelevant).
        verify(redis, org.mockito.Mockito.times(2)).execute(any(RedisScript.class),
                eq(List.of("ratelimit:internal-webhook:ip:203.0.113.9")), any(), any(), any());
    }
}
