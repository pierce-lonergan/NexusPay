package io.nexuspay.gateway.adapter.in.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.gateway.util.IdempotencyScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

/**
 * Idempotency enforcement for mutating (POST) requests.
 * Uses Valkey distributed lock + response cache:
 * 1. SET idempotency:{key} "PROCESSING" NX EX 60 (acquire lock)
 * 2. If NX succeeds → process → cache response for 24h
 * 3. If NX fails → return cached response or poll until ready
 *
 * Uses LockSupport.parkNanos instead of Thread.sleep for virtual thread
 * compatibility (GAP-031) — avoids pinning carrier threads.
 */
@Component
@Order(11)
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String PROCESSING_SENTINEL = "PROCESSING";
    private static final Duration LOCK_TTL = Duration.ofSeconds(60);
    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final int MAX_POLL_ATTEMPTS = 15;
    private static final long POLL_INTERVAL_MS = 200;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyFilter(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String idempotencyKey = request.getHeader(IDEMPOTENCY_HEADER);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        // Scope the key to the caller's credential. Without this, two different
        // merchants reusing a common Idempotency-Key (e.g. "order-1") collide and
        // merchant B receives merchant A's cached response body. The scope derivation is single-sourced in
        // IdempotencyScope so the test-mode inspect/clear controller can never look at a different scope.
        String redisKey = IdempotencyScope.keyPrefix(request) + idempotencyKey;

        try {
            // Try to acquire processing lock
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(redisKey, PROCESSING_SENTINEL, LOCK_TTL);

            if (Boolean.TRUE.equals(acquired)) {
                // We own the lock — process the request and cache the response
                processAndCache(request, response, filterChain, redisKey);
                return;
            }

            // Lock not acquired — another request is processing or completed
            String cached = pollForResult(redisKey);
            if (cached != null && !PROCESSING_SENTINEL.equals(cached)) {
                writeCachedResponse(response, cached);
                return;
            }

            // Timed out waiting — let the request through (idempotency key expired or crashed)
            log.warn("Idempotency poll timed out for key={}, processing normally", idempotencyKey);
            filterChain.doFilter(request, response);

        } catch (Exception e) {
            log.warn("Idempotency check failed, processing normally: {}", e.getMessage());
            filterChain.doFilter(request, response);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only apply to POST requests (mutating operations)
        return !"POST".equalsIgnoreCase(request.getMethod());
    }

    private void processAndCache(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain filterChain, String redisKey) throws IOException, ServletException {
        var wrappedResponse = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(request, wrappedResponse);

            int status = wrappedResponse.getStatus();
            // Only cache deterministic outcomes (2xx success, 4xx client errors).
            // A 5xx is typically transient — caching it would pin the failure to
            // the idempotency key for 24h so every legitimate retry replays the
            // error and can never succeed. Release the lock instead.
            if (status >= 500) {
                redisTemplate.delete(redisKey);
            } else {
                String contentType = wrappedResponse.getContentType();
                byte[] body = wrappedResponse.getContentAsByteArray();
                var cachedEntry = new CachedResponse(status, contentType, new String(body));
                String json = objectMapper.writeValueAsString(cachedEntry);
                redisTemplate.opsForValue().set(redisKey, json, CACHE_TTL);
            }

            wrappedResponse.copyBodyToResponse();
        } catch (Exception e) {
            // On error, delete the lock so retries can proceed
            redisTemplate.delete(redisKey);
            throw e;
        }
    }

    private String pollForResult(String redisKey) {
        for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
            // Use LockSupport.parkNanos instead of Thread.sleep to avoid pinning
            // carrier threads when running on virtual threads (GAP-031)
            LockSupport.parkNanos(Duration.ofMillis(POLL_INTERVAL_MS).toNanos());
            if (Thread.currentThread().isInterrupted()) return null;
            String value = redisTemplate.opsForValue().get(redisKey);
            if (value != null && !PROCESSING_SENTINEL.equals(value)) {
                return value;
            }
        }
        return null;
    }

    private void writeCachedResponse(HttpServletResponse response, String cached) throws IOException {
        var cachedResponse = objectMapper.readValue(cached, CachedResponse.class);
        response.setStatus(cachedResponse.status());
        if (cachedResponse.contentType() != null) {
            response.setContentType(cachedResponse.contentType());
        }
        response.getWriter().write(cachedResponse.body());
    }

    record CachedResponse(int status, String contentType, String body) {
    }
}
