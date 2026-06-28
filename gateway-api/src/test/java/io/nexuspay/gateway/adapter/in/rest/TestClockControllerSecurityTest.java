package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.nexuspay.payment.application.service.clock.TestClockService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GAP-078 (critique v3 F5): scope/role/gate enforcement on {@code /v1/test/clock} through the REAL
 * {@code @PreAuthorize} + {@code @scopeAuth} pipeline (mirrors {@code TestSandboxScopeEnforcementTest}).
 * L-068: a first-security {@code @WebMvcTest} slice needs spring-security-test + the auth-filter exclusion.
 *
 * <p>Confirms reuse of {@code ApiScope.TEST_WRITE} (NO new scope):</p>
 * <ul>
 *   <li>{@code test:write} + operator + TEST → 200 (allowed);</li>
 *   <li>{@code test:write} + LIVE → 404 (the test-mode gate wins, no oracle);</li>
 *   <li>a key with explicit scopes lacking {@code test:write} → 403;</li>
 *   <li>{@code test:write} + viewer role → 403 (AND-composition with role);</li>
 *   <li>UNRESTRICTED (null scopes) + TEST → 200 (back-compat).</li>
 * </ul>
 */
@WebMvcTest(controllers = TestClockController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "io\\.nexuspay\\.gateway\\.adapter\\.in\\.filter\\..*"))
class TestClockControllerSecurityTest {

    private static final String TENANT = "tenant-1";
    private static final String BODY = "{\"now\":\"2026-01-01T00:00:00Z\"}";

    @Autowired private MockMvc mockMvc;
    @MockBean private TestClockService testClockService;

    private static Authentication auth(String role, Set<String> scopes, boolean live) {
        var principal = new NexusPayPrincipal(
                "user-1", TENANT, role, NexusPayPrincipal.AuthMethod.API_KEY, null, live, scopes);
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void testWriteScopedTestKey_set_allowed_200() throws Exception {
        mockMvc.perform(put("/v1/test/clock")
                        .contentType("application/json").content(BODY)
                        .with(authentication(auth("operator", Set.of("test:write"), false))))
                .andExpect(status().isOk());
    }

    @Test
    void testWriteScopedTestKey_get_allowed_200() throws Exception {
        when(testClockService.get(anyString())).thenReturn(Optional.of(Instant.parse("2026-01-01T00:00:00Z")));
        mockMvc.perform(get("/v1/test/clock")
                        .with(authentication(auth("operator", Set.of("test:write"), false))))
                .andExpect(status().isOk());
    }

    @Test
    void testWriteScopedLiveKey_is404_gateWins() throws Exception {
        // scope + role pass; the test-mode gate then 404s a LIVE key (no oracle, no set).
        mockMvc.perform(put("/v1/test/clock")
                        .contentType("application/json").content(BODY)
                        .with(authentication(auth("operator", Set.of("test:write"), true))))
                .andExpect(status().isNotFound());
    }

    @Test
    void readScopedKey_withoutTestWrite_forbidden() throws Exception {
        mockMvc.perform(put("/v1/test/clock")
                        .contentType("application/json").content(BODY)
                        .with(authentication(auth("operator", Set.of("webhooks:read"), false))))
                .andExpect(status().isForbidden());
    }

    @Test
    void testWriteScopedKey_butViewerRole_forbidden() throws Exception {
        mockMvc.perform(put("/v1/test/clock")
                        .contentType("application/json").content(BODY)
                        .with(authentication(auth("viewer", Set.of("test:write"), false))))
                .andExpect(status().isForbidden());
    }

    @Test
    void unrestrictedTestKey_allowed_200() throws Exception {
        // back-compat: a null-scopes (unrestricted) key passes the scope check; TEST principal -> 200.
        mockMvc.perform(put("/v1/test/clock")
                        .contentType("application/json").content(BODY)
                        .with(authentication(auth("operator", null, false))))
                .andExpect(status().isOk());
    }
}
