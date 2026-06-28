package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.gateway.adapter.out.webhook.TestEventOutboxAdapter;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TEST-4a (D1): scope/role/gate enforcement on {@code POST /v1/test/events} through the REAL
 * {@code @PreAuthorize} pipeline (same @WebMvcTest + L-068 filter-exclusion + @scopeAuth bean pattern as
 * PaymentControllerScopeEnforcementTest).
 *
 * <ul>
 *   <li>a {@code webhooks:read}-only key → 403 (the trigger needs {@code webhooks:write});</li>
 *   <li>a {@code webhooks:write} key with a TEST principal (live=false) → 202 (allowed);</li>
 *   <li>a LIVE {@code webhooks:write} key → 404 (the test-mode gate wins, no oracle);</li>
 *   <li>AND-composition with role: right scope, viewer role → 403.</li>
 * </ul>
 */
@WebMvcTest(controllers = TestEventController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "io\\.nexuspay\\.gateway\\.adapter\\.in\\.filter\\..*"))
class TestEventScopeEnforcementTest {

    private static final String TENANT = "tenant-1";
    private static final String BODY = """
            { "type": "payment.succeeded" }
            """;

    @Autowired private MockMvc mockMvc;
    @MockBean private TestEventOutboxAdapter outbox;

    private static Authentication auth(String role, Set<String> scopes, boolean live) {
        var principal = new NexusPayPrincipal(
                "user-1", TENANT, role, NexusPayPrincipal.AuthMethod.API_KEY, null, live, scopes);
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void readScopedKey_forbidden() throws Exception {
        mockMvc.perform(post("/v1/test/events")
                        .with(authentication(auth("operator", Set.of("webhooks:read"), false)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void writeScopedTestKey_allowed_202() throws Exception {
        when(outbox.synthesize(eq(TENANT), anyString(), anyString(), anyString(), any(), eq(false)))
                .thenReturn("evt_1");

        mockMvc.perform(post("/v1/test/events")
                        .with(authentication(auth("operator", Set.of("webhooks:write"), false)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isAccepted());
    }

    @Test
    void writeScopedLiveKey_is404_gateWins() throws Exception {
        // The scope + role pass; the test-mode gate then 404s a LIVE key (no oracle the route exists).
        mockMvc.perform(post("/v1/test/events")
                        .with(authentication(auth("operator", Set.of("webhooks:write"), true)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void writeScopedKey_butViewerRole_forbidden() throws Exception {
        mockMvc.perform(post("/v1/test/events")
                        .with(authentication(auth("viewer", Set.of("webhooks:write"), false)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void unrestrictedTestKey_allowed_202() throws Exception {
        // back-compat: a null-scopes (unrestricted) key passes the scope check; TEST principal -> 202.
        when(outbox.synthesize(eq(TENANT), anyString(), anyString(), anyString(), any(), anyBoolean()))
                .thenReturn("evt_2");

        mockMvc.perform(post("/v1/test/events")
                        .with(authentication(auth("operator", null, false)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isAccepted());
    }
}
