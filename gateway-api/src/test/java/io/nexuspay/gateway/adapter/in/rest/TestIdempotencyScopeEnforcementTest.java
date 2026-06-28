package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.iam.domain.NexusPayPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GAP-079 (critique v3 F6): scope/role/gate enforcement on {@code GET}/{@code DELETE
 * /v1/test/idempotency-keys} through the REAL {@code @PreAuthorize} + {@code @scopeAuth} pipeline
 * (mirrors {@link TestSandboxScopeEnforcementTest} exactly — the proven GAP-077 pattern).
 *
 * <p>The {@link TestIdempotencyControllerTest} unit test direct-constructs the controller, which BYPASSES
 * Spring Security; it pins the {@code isTest()}→404 gate and the SCAN/IDOR behaviour but NEVER exercises the
 * {@code @PreAuthorize("hasAnyRole('admin','operator') and @scopeAuth.has('test:write')")} annotation. This
 * test closes that gap so a dropped/mistyped scope on a list/clear handler is caught.</p>
 *
 * <ul>
 *   <li>{@code test:write} + operator + TEST → 200/204 (allowed);</li>
 *   <li>{@code test:write} + LIVE → 404 (the test-mode gate wins, no oracle);</li>
 *   <li>{@code webhooks:read}-only (no {@code test:write}) → 403;</li>
 *   <li>{@code test:write} + viewer role → 403 (AND-composition with role);</li>
 *   <li>unrestricted (null scopes) + TEST → 200/204 (back-compat).</li>
 * </ul>
 */
@WebMvcTest(controllers = TestIdempotencyController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "io\\.nexuspay\\.gateway\\.adapter\\.in\\.filter\\..*"))
class TestIdempotencyScopeEnforcementTest {

    private static final String TENANT = "tenant-1";

    @Autowired private MockMvc mockMvc;
    @MockBean private StringRedisTemplate redisTemplate;

    private static Authentication auth(String role, Set<String> scopes, boolean live) {
        var principal = new NexusPayPrincipal(
                "user-1", TENANT, role, NexusPayPrincipal.AuthMethod.API_KEY, null, live, scopes);
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    /** An empty SCAN cursor so the allowed (gate-passing) cases 200/204 cleanly without iterating Redis. */
    @SuppressWarnings("unchecked")
    private void stubEmptyScan() {
        Cursor<String> empty = mock(Cursor.class);
        when(empty.hasNext()).thenReturn(false);
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(empty);
    }

    // -------- GET /v1/test/idempotency-keys (list) --------

    @Test
    void list_testWriteScopedTestKey_allowed_200() throws Exception {
        stubEmptyScan();
        mockMvc.perform(get("/v1/test/idempotency-keys")
                        .with(authentication(auth("operator", Set.of("test:write"), false))))
                .andExpect(status().isOk());
    }

    @Test
    void list_testWriteScopedLiveKey_is404_gateWins() throws Exception {
        // scope + role pass; the test-mode gate then 404s a LIVE key (no oracle, no scan).
        mockMvc.perform(get("/v1/test/idempotency-keys")
                        .with(authentication(auth("operator", Set.of("test:write"), true))))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_readScopedKey_withoutTestWrite_forbidden() throws Exception {
        mockMvc.perform(get("/v1/test/idempotency-keys")
                        .with(authentication(auth("operator", Set.of("webhooks:read"), false))))
                .andExpect(status().isForbidden());
    }

    @Test
    void list_testWriteScopedKey_butViewerRole_forbidden() throws Exception {
        mockMvc.perform(get("/v1/test/idempotency-keys")
                        .with(authentication(auth("viewer", Set.of("test:write"), false))))
                .andExpect(status().isForbidden());
    }

    @Test
    void list_unrestrictedTestKey_allowed_200() throws Exception {
        // back-compat: a null-scopes (unrestricted) key passes the scope check; TEST principal -> 200.
        stubEmptyScan();
        mockMvc.perform(get("/v1/test/idempotency-keys")
                        .with(authentication(auth("operator", null, false))))
                .andExpect(status().isOk());
    }

    // -------- DELETE /v1/test/idempotency-keys (clearAll) --------

    @Test
    void clearAll_testWriteScopedTestKey_allowed_204() throws Exception {
        stubEmptyScan();
        mockMvc.perform(delete("/v1/test/idempotency-keys")
                        .with(authentication(auth("operator", Set.of("test:write"), false))))
                .andExpect(status().isNoContent());
    }

    @Test
    void clearAll_testWriteScopedLiveKey_is404_gateWins() throws Exception {
        mockMvc.perform(delete("/v1/test/idempotency-keys")
                        .with(authentication(auth("operator", Set.of("test:write"), true))))
                .andExpect(status().isNotFound());
    }

    @Test
    void clearAll_readScopedKey_withoutTestWrite_forbidden() throws Exception {
        mockMvc.perform(delete("/v1/test/idempotency-keys")
                        .with(authentication(auth("operator", Set.of("webhooks:read"), false))))
                .andExpect(status().isForbidden());
    }

    @Test
    void clearAll_testWriteScopedKey_butViewerRole_forbidden() throws Exception {
        mockMvc.perform(delete("/v1/test/idempotency-keys")
                        .with(authentication(auth("viewer", Set.of("test:write"), false))))
                .andExpect(status().isForbidden());
    }

    @Test
    void clearAll_unrestrictedTestKey_allowed_204() throws Exception {
        stubEmptyScan();
        mockMvc.perform(delete("/v1/test/idempotency-keys")
                        .with(authentication(auth("operator", null, false))))
                .andExpect(status().isNoContent());
    }

    // -------- DELETE /v1/test/idempotency-keys/{key} (clearOne) --------

    @Test
    void clearOne_testWriteScopedTestKey_allowed_204() throws Exception {
        mockMvc.perform(delete("/v1/test/idempotency-keys/{key}", "order-1")
                        .with(authentication(auth("operator", Set.of("test:write"), false))))
                .andExpect(status().isNoContent());
    }

    @Test
    void clearOne_testWriteScopedLiveKey_is404_gateWins() throws Exception {
        mockMvc.perform(delete("/v1/test/idempotency-keys/{key}", "order-1")
                        .with(authentication(auth("operator", Set.of("test:write"), true))))
                .andExpect(status().isNotFound());
    }

    @Test
    void clearOne_readScopedKey_withoutTestWrite_forbidden() throws Exception {
        mockMvc.perform(delete("/v1/test/idempotency-keys/{key}", "order-1")
                        .with(authentication(auth("operator", Set.of("webhooks:read"), false))))
                .andExpect(status().isForbidden());
    }

    @Test
    void clearOne_unrestrictedTestKey_allowed_204() throws Exception {
        mockMvc.perform(delete("/v1/test/idempotency-keys/{key}", "order-1")
                        .with(authentication(auth("operator", null, false))))
                .andExpect(status().isNoContent());
    }
}
