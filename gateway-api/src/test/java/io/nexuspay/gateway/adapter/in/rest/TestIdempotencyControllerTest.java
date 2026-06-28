package io.nexuspay.gateway.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.tenant.LiveModePrincipal;
import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.gateway.adapter.in.rest.dto.IdempotencyKeyView;
import io.nexuspay.gateway.util.IdempotencyScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
 * GAP-079 (critique v3 F6): the inspect/clear controller against a mocked {@link StringRedisTemplate}
 * (mirrors {@code IdempotencyFilterTest}'s mocking). Proves: (a) ISOLATION — a caller's GET/DELETE only ever
 * touches keys under THEIR OWN scope prefix (the IDOR-by-construction guarantee); (b) SCAN (cursor) is used,
 * never KEYS; (c) the processing/cached status + http_status + ttl shape; (d) the {@code isTest()}→404 gate
 * for a LIVE principal.
 */
class TestIdempotencyControllerTest {

    private static final String AUTH_A = "Bearer merchantA";
    private static final String AUTH_B = "Bearer merchantB";

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ObjectMapper objectMapper;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @AfterEach
    void clearCtx() {
        SecurityContextHolder.clearContext();
    }

    private record TestPrincipal(String tenant, boolean live)
            implements TenantPrincipal, LiveModePrincipal {
        @Override public String tenantId() { return tenant; }
        @Override public boolean live() { return live; }
    }

    private void authenticate(boolean live) {
        var auth = new UsernamePasswordAuthenticationToken(
                new TestPrincipal("t1", live), "n/a", java.util.List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static MockHttpServletRequest reqWithAuth(String auth) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/v1/test/idempotency-keys");
        r.addHeader("Authorization", auth);
        return r;
    }

    /** A minimal Cursor<String> over a fixed list (only the methods the controller uses are real). */
    private static Cursor<String> cursorOver(List<String> keys) {
        Iterator<String> it = keys.iterator();
        @SuppressWarnings("unchecked")
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenAnswer(inv -> it.hasNext());
        when(cursor.next()).thenAnswer(inv -> it.next());
        return cursor;
    }

    private TestIdempotencyController controller(MockHttpServletRequest req) {
        return new TestIdempotencyController(redis, objectMapper, req);
    }

    // -------- ISOLATION: caller A only ever scans/sees keys under A's scope prefix --------

    @Test
    void list_returnsOnlyCallersScopedKeys_viaScanNotKeys() {
        authenticate(false); // TEST
        MockHttpServletRequest req = reqWithAuth(AUTH_A);
        String prefixA = IdempotencyScope.keyPrefix(req); // idempotency:<hashA>:
        String keyA1 = prefixA + "order-1";
        String keyA2 = prefixA + "order-2";

        when(redis.scan(any(ScanOptions.class))).thenReturn(cursorOver(List.of(keyA1, keyA2)));
        when(valueOps.get(keyA1)).thenReturn("PROCESSING");
        when(valueOps.get(keyA2)).thenReturn("{\"status\":201,\"contentType\":\"application/json\",\"body\":\"{}\"}");
        when(redis.getExpire(keyA1, TimeUnit.SECONDS)).thenReturn(60L);
        when(redis.getExpire(keyA2, TimeUnit.SECONDS)).thenReturn(86392L);

        ResponseEntity<List<IdempotencyKeyView>> resp = controller(req).list();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<IdempotencyKeyView> views = resp.getBody();
        assertThat(views).hasSize(2);
        // keys are returned with the scope prefix STRIPPED (only the idempotency key value).
        assertThat(views).extracting(IdempotencyKeyView::key)
                .containsExactlyInAnyOrder("order-1", "order-2");

        // processing entry: status=processing, http_status=null, ttl carried.
        IdempotencyKeyView a1 = views.stream().filter(v -> v.key().equals("order-1")).findFirst().orElseThrow();
        assertThat(a1.status()).isEqualTo("processing");
        assertThat(a1.httpStatus()).isNull();
        assertThat(a1.ttlSeconds()).isEqualTo(60L);
        // cached entry: status=cached, http_status parsed from the CachedResponse JSON, ttl carried.
        IdempotencyKeyView a2 = views.stream().filter(v -> v.key().equals("order-2")).findFirst().orElseThrow();
        assertThat(a2.status()).isEqualTo("cached");
        assertThat(a2.httpStatus()).isEqualTo(201);
        assertThat(a2.ttlSeconds()).isEqualTo(86392L);

        // SCAN (cursor) was used with the caller's OWN scoped pattern; KEYS was never used.
        ArgumentCaptor<ScanOptions> opts = ArgumentCaptor.forClass(ScanOptions.class);
        verify(redis).scan(opts.capture());
        assertThat(opts.getValue().getPattern()).isEqualTo(prefixA + "*");
        verify(redis, never()).keys(anyString());
    }

    @Test
    void list_scanPattern_isScopedToTheCaller_neverAnotherScope() {
        authenticate(false);
        MockHttpServletRequest reqA = reqWithAuth(AUTH_A);
        MockHttpServletRequest reqB = reqWithAuth(AUTH_B);
        String prefixA = IdempotencyScope.keyPrefix(reqA);
        String prefixB = IdempotencyScope.keyPrefix(reqB);
        // sanity: the two callers derive DIFFERENT scopes.
        assertThat(prefixA).isNotEqualTo(prefixB);

        when(redis.scan(any(ScanOptions.class))).thenReturn(cursorOver(List.of()));

        controller(reqA).list();

        ArgumentCaptor<ScanOptions> opts = ArgumentCaptor.forClass(ScanOptions.class);
        verify(redis).scan(opts.capture());
        // A's scan pattern carries A's scope and NEVER B's.
        assertThat(opts.getValue().getPattern()).isEqualTo(prefixA + "*");
        assertThat(opts.getValue().getPattern()).doesNotContain(prefixB);
    }

    // -------- CLEAR: deletes only the caller's scoped keys, leaves another scope untouched --------

    @Test
    void clearAll_deletesOnlyCallersScopedKeys() {
        authenticate(false);
        MockHttpServletRequest reqA = reqWithAuth(AUTH_A);
        String prefixA = IdempotencyScope.keyPrefix(reqA);
        String keyA1 = prefixA + "order-1";
        String keyA2 = prefixA + "order-2";
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursorOver(List.of(keyA1, keyA2)));
        when(redis.delete(anyString())).thenReturn(true);

        ResponseEntity<Void> resp = controller(reqA).clearAll();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // every deleted key carries A's scope prefix (bounded to the caller's own scope).
        ArgumentCaptor<String> deleted = ArgumentCaptor.forClass(String.class);
        verify(redis, times(2)).delete(deleted.capture());
        assertThat(deleted.getAllValues()).allMatch(k -> k.startsWith(prefixA));
    }

    @Test
    void clearOne_deletesTheCallerScopedSingleKey() {
        authenticate(false);
        MockHttpServletRequest reqA = reqWithAuth(AUTH_A);
        String prefixA = IdempotencyScope.keyPrefix(reqA);

        controller(reqA).clearOne("order-9");

        verify(redis).delete(eq(prefixA + "order-9"));
    }

    // -------- GATE: a LIVE principal gets 404 on every handler, and never touches Redis --------

    @Test
    void liveKey_list_is404_andNeverScans() {
        authenticate(true); // LIVE
        ResponseEntity<List<IdempotencyKeyView>> resp = controller(reqWithAuth(AUTH_A)).list();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(redis, never()).scan(any(ScanOptions.class));
    }

    @Test
    void liveKey_clearAll_is404_andNeverScans() {
        authenticate(true);
        ResponseEntity<Void> resp = controller(reqWithAuth(AUTH_A)).clearAll();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(redis, never()).scan(any(ScanOptions.class));
        verify(redis, never()).delete(anyString());
    }

    @Test
    void liveKey_clearOne_is404_andNeverDeletes() {
        // clearOne carries the same isTest()->404 guard; a LIVE key must 404 and never touch Redis.
        authenticate(true);
        ResponseEntity<Void> resp = controller(reqWithAuth(AUTH_A)).clearOne("order-9");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(redis, never()).delete(anyString());
    }
}
