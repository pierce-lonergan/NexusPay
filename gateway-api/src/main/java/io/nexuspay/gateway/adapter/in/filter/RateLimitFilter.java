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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Token bucket rate limiter using Valkey/Redis.
 * Limits requests per API key (or per authenticated user for JWT).
 * Default: 100 requests per 60-second window.
 */
@Component
@Order(10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final int maxRequests;
    private final int windowSeconds;

    // Lua script for atomic token bucket: returns [allowed (1/0), remaining, retry_after_seconds]
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

    public RateLimitFilter(StringRedisTemplate redisTemplate,
                            ObjectMapper objectMapper,
                            @Value("${nexuspay.rate-limit.max-requests:100}") int maxRequests,
                            @Value("${nexuspay.rate-limit.window-seconds:60}") int windowSeconds) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String rateLimitKey = resolveRateLimitKey();
        if (rateLimitKey == null) {
            // No authenticated principal — skip rate limiting (auth filter will reject)
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String redisKey = "ratelimit:" + rateLimitKey;
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
            // If Valkey is down, allow the request (fail open for availability)
            log.warn("Rate limit check failed, allowing request: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/") ||
               path.startsWith("/internal/") ||
               path.startsWith("/v1/api-docs") ||
               path.startsWith("/v1/swagger-ui");
    }

    private String resolveRateLimitKey() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            return null;
        }
        return auth.getName();
    }

    private void writeRateLimitResponse(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        var error = ApiErrorResponse.of(ApiError.rateLimitError());
        objectMapper.writeValue(response.getOutputStream(), error);
    }
}
