package io.nexuspay.gateway.adapter.in.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.domain.ApiError;
import io.nexuspay.common.domain.ApiErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * SEC-21: per-IP token-bucket rate limiter for the inbound webhook endpoints under
 * {@code /internal/webhooks/**} ({@code /hyperswitch}, {@code /disputes}).
 *
 * <p><b>Why a separate filter.</b> The existing {@link RateLimitFilter} (@Order 10) keys on the
 * authenticated principal and explicitly {@code shouldNotFilter}s {@code /internal/} — so the
 * HMAC-only webhook endpoints have NO rate limit and are exposed to an unauthenticated flood /
 * HMAC brute-force / DoS BEFORE the controller's signature check ever runs. This filter is keyed on
 * the client IP and runs at {@code @Order(9)} — BEFORE {@link RateLimitFilter} (@Order 10) and BEFORE
 * the controller HMAC verification — so abusive IPs are shed before any signature work. (Spring
 * Security's FilterChainProxy registers earlier, at {@code SecurityProperties.DEFAULT_FILTER_ORDER}
 * = -100, so it technically runs first; but {@code /internal/webhooks/**} is {@code permitAll()}, so
 * this filter is the FIRST EFFECTIVE gate on the unauthenticated webhook flood.) It is intentionally
 * additive: {@link RateLimitFilter} and its test are untouched.</p>
 *
 * <p><b>Fail-OPEN on Redis error.</b> Like {@link RateLimitFilter}, when Valkey is unreachable the
 * request is allowed through. A webhook limiter that failed CLOSED would drop real payment/dispute
 * events from the PSP during a Redis outage — silently losing money-movement signals is worse than a
 * brief loss of flood protection (the HMAC check still gates authenticity). Availability of genuine
 * webhooks wins.</p>
 *
 * <p><b>Sizing the per-IP limit (SEC-BATCH-5c).</b> The key is the client IP, and PSPs (HyperSwitch,
 * the dispute provider) deliver from a small, FIXED set of egress IPs — often a single source IP — so
 * this is effectively a per-PSP limit, not per-attacker. The default {@code 600/60s} (10/s) is set HIGH
 * on purpose: a real resource-exhaustion / brute-force flood runs orders of magnitude above it, so 600
 * and 60 shed a flood equally, but 60/min (1/s) would needlessly 429 a legitimate PSP burst. It is the
 * threshold above which an UNKNOWN flooding IP is shed before the controller HMAC check, NOT a cap
 * intended to throttle the PSP. If a real PSP burst (end-of-day batch captures/refunds, a dispute
 * storm) can plausibly exceed this from one egress IP, do NOT lower it —
 * raise {@code nexuspay.internal-webhook-rate-limit.max-requests} above the documented peak legitimate
 * delivery rate (PSP max delivery rate × merchant fan-out + headroom). A 429 is retryable so events are
 * delayed not lost, but repeated 429s during a burst stall money movement — exactly when webhooks
 * matter most — so the env override exists to tune this per deployment once real PSP volume is known.
 * A follow-up (tracked) is a PSP-source-IP allowlist/bypass so only genuinely unknown floods are shed;
 * until then the global override is the knob.</p>
 */
@Component
@Order(9)
public class InternalWebhookRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalWebhookRateLimitFilter.class);

    /** Only these paths are rate limited; everything else is skipped via {@link #shouldNotFilter}. */
    private static final String INTERNAL_WEBHOOK_PREFIX = "/internal/webhooks";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final int maxRequests;
    private final int windowSeconds;
    private final boolean trustForwardedFor;

    // Private copy of the atomic token-bucket Lua used by RateLimitFilter (safest to not share state /
    // coupling across filters). Returns [allowed (1/0), remaining, retry_after_seconds].
    private static final RedisScript<List> RATE_LIMIT_SCRIPT = RedisScript.of("""
            local key = KEYS[1]
            local max_requests = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])

            local bucket = redis.call('GET', key)
            if bucket then
                local data = cjson.decode(bucket)
                local elapsed = now - data.ts
                local refill = math.floor(elapsed * max_requests / window)
                data.tokens = math.min(max_requests, data.tokens + refill)
                data.ts = now

                if data.tokens > 0 then
                    data.tokens = data.tokens - 1
                    redis.call('SET', key, cjson.encode(data), 'EX', window)
                    return {1, data.tokens, 0}
                else
                    local retry_after = math.ceil(window / max_requests)
                    return {0, 0, retry_after}
                end
            else
                local data = {tokens = max_requests - 1, ts = now}
                redis.call('SET', key, cjson.encode(data), 'EX', window)
                return {1, max_requests - 1, 0}
            end
            """, List.class);

    public InternalWebhookRateLimitFilter(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${nexuspay.internal-webhook-rate-limit.max-requests:600}") int maxRequests,
            @Value("${nexuspay.internal-webhook-rate-limit.window-seconds:60}") int windowSeconds,
            @Value("${nexuspay.internal-webhook-rate-limit.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
        this.trustForwardedFor = trustForwardedFor;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String clientIp = clientIp(request);

        try {
            String redisKey = "ratelimit:internal-webhook:ip:" + clientIp;
            long now = System.currentTimeMillis() / 1000;

            @SuppressWarnings("unchecked")
            List<Long> result = redisTemplate.execute(RATE_LIMIT_SCRIPT,
                    List.of(redisKey),
                    String.valueOf(maxRequests),
                    String.valueOf(windowSeconds),
                    String.valueOf(now));

            if (result != null && !result.isEmpty()) {
                long allowed = result.get(0);
                long remaining = result.get(1);
                long retryAfter = result.get(2);

                response.setHeader("X-RateLimit-Limit", String.valueOf(maxRequests));
                response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));

                if (allowed == 0) {
                    response.setHeader("Retry-After", String.valueOf(retryAfter));
                    writeRateLimitResponse(response);
                    return;
                }
            }
        } catch (Exception e) {
            // Fail OPEN: a webhook limiter that fails closed would DROP real PSP payment/dispute events
            // during a Redis outage. The HMAC check downstream still gates authenticity.
            log.warn("Internal-webhook rate limit check failed, allowing request: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Limit ONLY the inbound webhook endpoints; skip everything else (the per-principal
        // RateLimitFilter@10 handles the rest of the API).
        return !request.getRequestURI().startsWith(INTERNAL_WEBHOOK_PREFIX);
    }

    /**
     * Resolves the client IP, defending against X-Forwarded-For spoofing.
     *
     * <p>{@code trust-forwarded-for} defaults to {@code false} — the limiter then uses
     * {@link HttpServletRequest#getRemoteAddr()} (the actual TCP peer), which a client cannot spoof.
     * Only set it {@code true} when the app is deployed behind EXACTLY ONE trusted load balancer that
     * appends the real client IP to {@code X-Forwarded-For}. In that topology the RIGHTMOST XFF entry
     * is the IP the trusted proxy itself observed and appended — every entry to its LEFT is
     * attacker-controlled (a client can send {@code X-Forwarded-For: 1.2.3.4} and the LB merely appends
     * to it). So we take the rightmost entry for spoof-resistance, never the leftmost. If XFF is absent
     * or unparseable we fall back to {@code getRemoteAddr()} rather than trusting garbage.</p>
     */
    private String clientIp(HttpServletRequest request) {
        if (trustForwardedFor) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String[] parts = xff.split(",");
                // Rightmost non-blank entry = the hop the single trusted proxy appended (spoof-resistant).
                for (int i = parts.length - 1; i >= 0; i--) {
                    String candidate = parts[i].trim();
                    if (!candidate.isEmpty()) {
                        return candidate;
                    }
                }
            }
            // XFF absent/unparseable: fall back rather than trust a spoofable / empty header.
        }
        return request.getRemoteAddr();
    }

    private void writeRateLimitResponse(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // SEC-21: match RateLimitFilter's 429 envelope exactly — common ApiError(TYPE_RATE_LIMIT,
        // rate_limit_exceeded, ...) with the request_id from the correlation MDC (UUID fallback so it is
        // never blank).
        String rid = MDC.get(CorrelationIdFilter.MDC_REQUEST_ID);
        if (rid == null || rid.isBlank()) {
            rid = UUID.randomUUID().toString();
        }
        var error = ApiErrorResponse.of(ApiError.of(ApiError.TYPE_RATE_LIMIT, "rate_limit_exceeded",
                "Too many requests. Please retry after the period specified in the Retry-After header.", rid));
        objectMapper.writeValue(response.getOutputStream(), error);
    }
}
