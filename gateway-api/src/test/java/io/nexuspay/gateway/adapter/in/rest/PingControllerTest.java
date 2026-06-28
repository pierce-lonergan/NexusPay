package io.nexuspay.gateway.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.api.ApiVersion;
import io.nexuspay.common.tenant.LiveModePrincipal;
import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.gateway.adapter.in.rest.dto.PingResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEST-5 (E3): pins {@code GET /v1/ping} via DIRECT construction (mirrors {@link TestEventControllerTest}).
 * Asserts: a TEST key -> 200 / {@code ok=true} / {@code livemode=false} / {@code api_version ==
 * ApiVersion.CURRENT}; a LIVE key -> {@code livemode=true}; and the response record has NO tenant component
 * (reflective + compile-time) so it cannot leak the tenant.
 *
 * <p>{@code api_version} is asserted against {@link ApiVersion#CURRENT}, never a hardcoded date string.</p>
 *
 * <p>The AUTH boundary (401/403 for an unauthenticated request, plus {@code @PreAuthorize("isAuthenticated()")}
 * actually gating the route) is driven through the REAL Spring Security filter chain by the sibling
 * {@link PingAuthEnforcementTest} {@code @WebMvcTest} slice (mirroring {@link TestEventScopeEnforcementTest}).</p>
 *
 * <p>L-071: the ObjectMapper here registers modules (kept honest for any Instant serialization), though
 * {@link PingResponse} carries no Instant; no server-minted id is asserted (the response has none).</p>
 */
class PingControllerTest {

    private static final String TENANT = "t1";

    private final PingController controller = new PingController();

    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private record TestPrincipal(String tenant, boolean live)
            implements TenantPrincipal, LiveModePrincipal {
        @Override public String tenantId() { return tenant; }
        @Override public boolean live() { return live; }
    }

    private void authenticateAs(String tenant, boolean live) {
        var auth = new UsernamePasswordAuthenticationToken(
                new TestPrincipal(tenant, live), "n/a", java.util.List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testKey_returns200_okTrue_livemodeFalse_currentApiVersion() {
        authenticateAs(TENANT, false); // TEST key

        ResponseEntity<PingResponse> resp = controller.ping();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        PingResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.ok()).isTrue();
        assertThat(body.livemode()).isFalse();
        // Single-sourced — referenced, NOT a hardcoded date string.
        assertThat(body.api_version()).isEqualTo(ApiVersion.CURRENT);
    }

    @Test
    void liveKey_livemodeTrue() {
        authenticateAs(TENANT, true); // LIVE key

        PingResponse body = controller.ping().getBody();

        assertThat(body).isNotNull();
        assertThat(body.livemode()).isTrue();
    }

    @Test
    void ping_carriesIsAuthenticatedPreAuthorize() throws NoSuchMethodException {
        // Belt-and-suspenders for the auth boundary that PingAuthEnforcementTest drives through the
        // filter chain: removing @PreAuthorize("isAuthenticated()") from the handler breaks this too.
        PreAuthorize preAuthorize = PingController.class.getMethod("ping").getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("isAuthenticated()");
    }

    @Test
    void response_hasNoTenantComponent_cannotLeakTenant() {
        RecordComponent[] components = PingResponse.class.getRecordComponents();
        assertThat(Arrays.stream(components).map(RecordComponent::getName))
                .containsExactlyInAnyOrder("ok", "livemode", "api_version")
                .doesNotContain("tenant", "tenant_id", "tenantId");
    }
}
