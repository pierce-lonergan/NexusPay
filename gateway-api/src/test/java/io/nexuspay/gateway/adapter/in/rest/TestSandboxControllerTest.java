package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.common.tenant.LiveModePrincipal;
import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.gateway.adapter.in.rest.dto.SandboxResetResponse;
import io.nexuspay.payment.application.service.sandbox.SandboxResetService;
import io.nexuspay.payment.application.service.sandbox.SandboxResetSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GAP-077 (critique v3 F4): pins the sandbox-reset HARD GATES via direct construction (mirrors
 * {@link TestEventControllerTest}). A LIVE key is 404 (no oracle) and the service NEVER runs; a TEST key
 * resets UNDER the caller's principal tenant (never a body/header) and returns the summary as snake_case.
 */
class TestSandboxControllerTest {

    private static final String TENANT = "t1";

    private final SandboxResetService service = mock(SandboxResetService.class);
    private final TestSandboxController controller = new TestSandboxController(service);

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
    void testKey_resetsUnderCallerTenant_returnsSummary200() {
        authenticateAs(TENANT, false); // TEST key
        when(service.reset(TENANT)).thenReturn(new SandboxResetSummary(2, 1, 5, 7, 3));

        ResponseEntity<SandboxResetResponse> resp = controller.reset();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        SandboxResetResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.payments()).isEqualTo(2);
        assertThat(body.refunds()).isEqualTo(1);
        assertThat(body.customers()).isEqualTo(5);
        assertThat(body.paymentMethods()).isEqualTo(7);
        assertThat(body.mandates()).isEqualTo(3);
        // The tenant is the principal's — never a body/header. Verify the exact tenant string passed.
        verify(service).reset(TENANT);
    }

    @Test
    void liveKey_is404_andNeverResets() {
        authenticateAs(TENANT, true); // LIVE key

        ResponseEntity<SandboxResetResponse> resp = controller.reset();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(service, never()).reset(anyString());
    }
}
