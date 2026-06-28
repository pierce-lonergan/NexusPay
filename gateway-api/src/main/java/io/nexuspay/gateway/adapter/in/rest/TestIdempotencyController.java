package io.nexuspay.gateway.adapter.in.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.tenant.CallerMode;
import io.nexuspay.gateway.adapter.in.rest.dto.IdempotencyKeyView;
import io.nexuspay.gateway.util.IdempotencyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * GAP-079 (critique v3 F6): the TEST-mode IDEMPOTENCY-KEY INSPECT + CLEAR control, so a test can re-run an
 * idempotent request after clearing its cached entry. Lists / clears ONLY the CALLER's own keys.
 *
 * <h3>Invariants (the review hunts these)</h3>
 * <ul>
 *   <li><b>UNREACHABLE by a live key.</b> Every handler is hard-gated on {@link CallerMode#isTest()} FIRST —
 *       a LIVE key (or any non-test principal) gets a 404 (no oracle the route exists, fail-closed).</li>
 *   <li><b>Caller-scoped (IDOR-safe by construction).</b> The Redis scope is recomputed from THIS request's
 *       {@code Authorization} header via {@link IdempotencyScope} — the SAME single-sourced derivation the
 *       {@code IdempotencyFilter} used to WRITE the keys, so the two can never drift. A caller can only ever
 *       match keys under their own Authorization-hash; another tenant's keys are uncomputable.</li>
 *   <li><b>SCAN, not KEYS.</b> Enumeration uses a cursor-based {@code SCAN} with the caller's scoped match
 *       pattern (KEYS is O(N) and blocks the server). The clear iterates the FULL cursor so no key is left
 *       behind.</li>
 *   <li><b>Least-privilege scope.</b> {@code @scopeAuth.has('test:write')} — the same dedicated
 *       sandbox-control scope as the reset; the in-method {@code isTest()} gate runs after
 *       {@code @PreAuthorize}, so a live key still 404s (the gate wins).</li>
 * </ul>
 *
 * @since GAP-079
 */
@RestController
@RequestMapping("/v1/test/idempotency-keys")
@Tag(name = "Test Helpers", description = "Test-mode-only control endpoints (reachable ONLY with an sk_test_ key)")
public class TestIdempotencyController {

    private static final Logger log = LoggerFactory.getLogger(TestIdempotencyController.class);

    private static final String PROCESSING_SENTINEL = "PROCESSING";
    private static final String STATUS_PROCESSING = "processing";
    private static final String STATUS_CACHED = "cached";
    /** SCAN batch hint — keep the server's per-cursor work bounded. */
    private static final int SCAN_COUNT = 256;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final HttpServletRequest request;

    public TestIdempotencyController(StringRedisTemplate redisTemplate,
                                     ObjectMapper objectMapper,
                                     HttpServletRequest request) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.request = request;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('admin', 'operator') and @scopeAuth.has('test:write')")
    @Operation(summary = "List the caller's idempotency keys (TEST mode only)")
    public ResponseEntity<List<IdempotencyKeyView>> list() {
        if (!CallerMode.isTest()) {
            return ResponseEntity.notFound().build();
        }
        String prefix = IdempotencyScope.keyPrefix(request);
        List<IdempotencyKeyView> views = new ArrayList<>();
        for (String fullKey : scanCallerKeys(prefix)) {
            String idempotencyKey = fullKey.substring(prefix.length());
            String value = redisTemplate.opsForValue().get(fullKey);
            Long ttl = redisTemplate.getExpire(fullKey, TimeUnit.SECONDS);
            long ttlSeconds = ttl != null ? ttl : -2L;

            if (value == null || PROCESSING_SENTINEL.equals(value)) {
                // Missing (raced expiry) or in-flight -> processing, no cached http status.
                views.add(new IdempotencyKeyView(idempotencyKey, STATUS_PROCESSING, null, ttlSeconds));
            } else {
                Integer httpStatus = parseCachedStatus(value);
                views.add(new IdempotencyKeyView(idempotencyKey, STATUS_CACHED, httpStatus, ttlSeconds));
            }
        }
        return ResponseEntity.ok(views);
    }

    @DeleteMapping
    @PreAuthorize("hasAnyRole('admin', 'operator') and @scopeAuth.has('test:write')")
    @Operation(summary = "Clear ALL of the caller's idempotency keys (TEST mode only)")
    public ResponseEntity<Void> clearAll() {
        if (!CallerMode.isTest()) {
            return ResponseEntity.notFound().build();
        }
        String prefix = IdempotencyScope.keyPrefix(request);
        int cleared = 0;
        for (String fullKey : scanCallerKeys(prefix)) {
            // Bounded to the caller's own scope: every key carries this caller's Authorization-hash prefix.
            Boolean deleted = redisTemplate.delete(fullKey);
            if (Boolean.TRUE.equals(deleted)) {
                cleared++;
            }
        }
        log.info("Cleared {} idempotency key(s) for caller scope (test mode)", cleared);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{key}")
    @PreAuthorize("hasAnyRole('admin', 'operator') and @scopeAuth.has('test:write')")
    @Operation(summary = "Clear a single idempotency key by value (TEST mode only)")
    public ResponseEntity<Void> clearOne(@PathVariable("key") String key) {
        if (!CallerMode.isTest()) {
            return ResponseEntity.notFound().build();
        }
        // Reconstruct the caller-scoped full key — never a client-controlled scope, only the {key} segment.
        String fullKey = IdempotencyScope.keyPrefix(request) + key;
        redisTemplate.delete(fullKey);
        return ResponseEntity.noContent().build();
    }

    /**
     * Enumerates the caller's keys via a FULL-cursor Redis {@code SCAN} on {@code prefix + "*"} (NEVER
     * {@code KEYS}). Dedups across cursor batches (SCAN may return duplicates). The pattern is the caller's
     * own scope prefix, so only the caller's keys are ever returned.
     */
    private Set<String> scanCallerKeys(String prefix) {
        Set<String> keys = new LinkedHashSet<>();
        ScanOptions options = ScanOptions.scanOptions().match(prefix + "*").count(SCAN_COUNT).build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }
        return keys;
    }

    /** Parses the cached-response JSON and returns its {@code status}, or {@code null} if unparseable. */
    private Integer parseCachedStatus(String value) {
        try {
            return objectMapper.readValue(value, CachedStatus.class).status();
        } catch (Exception e) {
            // A value we cannot parse as a CachedResponse -> surface no http_status rather than fail the list.
            log.debug("Idempotency value not a parseable CachedResponse: {}", e.getMessage());
            return null;
        }
    }

    /** Minimal projection of the filter's {@code CachedResponse} JSON — only {@code status} is needed here. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CachedStatus(int status) {
    }
}
