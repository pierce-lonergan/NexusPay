package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.common.api.ApiVersion;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TEST-5 (E3): drives {@code GET /v1/ping} through the REAL Spring Security {@code @PreAuthorize} pipeline
 * (same {@code @WebMvcTest} + L-068 filter-exclusion pattern as {@link TestEventScopeEnforcementTest}).
 *
 * <p>This is the AUTH-boundary companion to the direct-construction {@link PingControllerTest}: the unit
 * test proves the body shape / livemode / no-tenant-leak, this slice proves that {@code @PreAuthorize(
 * "isAuthenticated()")} actually gates the route — so an unauthenticated request is rejected and a future
 * regression (dropping {@code @PreAuthorize}, or adding {@code /v1/ping} to a {@code permitAll()} list)
 * breaks a test instead of silently shipping an unauth 200 that leaks {@code livemode}.</p>
 *
 * <ul>
 *   <li>NO authentication -> 401/403 (anonymous is rejected before the handler);</li>
 *   <li>a TEST key (live=false) -> 200 + {@code livemode=false} + {@code api_version == ApiVersion.CURRENT};</li>
 *   <li>a LIVE key (live=true) -> 200 + {@code livemode=true};</li>
 *   <li>the 200 body carries NO tenant field (no-leak).</li>
 * </ul>
 */
@WebMvcTest(controllers = PingController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "io\\.nexuspay\\.gateway\\.adapter\\.in\\.filter\\..*"))
class PingAuthEnforcementTest {

    private static final String TENANT = "tenant-1";

    @Autowired private MockMvc mockMvc;

    private static Authentication apiKey(boolean live) {
        var principal = new NexusPayPrincipal(
                "user-1", TENANT, "operator", NexusPayPrincipal.AuthMethod.API_KEY, null, live, null);
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_operator")));
    }

    @Test
    void anonymous_isRejected() throws Exception {
        // No .with(authentication(...)) -> the filter chain rejects before the handler runs.
        int statusCode = mockMvc.perform(get("/v1/ping"))
                .andReturn().getResponse().getStatus();
        // @PreAuthorize on an anonymous request surfaces as 401 (entry point) or 403 depending on the
        // chain; either proves the route is gated and NOT a public 200.
        org.assertj.core.api.Assertions.assertThat(statusCode).isIn(401, 403);
    }

    @Test
    void testKey_authenticated_200_livemodeFalse_noTenantLeak() throws Exception {
        mockMvc.perform(get("/v1/ping").with(authentication(apiKey(false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.livemode").value(false))
                .andExpect(jsonPath("$.api_version").value(ApiVersion.CURRENT))
                // no-leak: the response must not expose the tenant under any plausible key.
                .andExpect(jsonPath("$.tenant").doesNotExist())
                .andExpect(jsonPath("$.tenant_id").doesNotExist())
                .andExpect(jsonPath("$.tenantId").doesNotExist());
    }

    @Test
    void liveKey_authenticated_200_livemodeTrue() throws Exception {
        mockMvc.perform(get("/v1/ping").with(authentication(apiKey(true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.livemode").value(true));
    }
}
