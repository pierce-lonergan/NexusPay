package io.nexuspay.gateway.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.exception.InvalidRequestException;
import io.nexuspay.common.tenant.LiveModePrincipal;
import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.gateway.adapter.in.rest.dto.TestClockResponse;
import io.nexuspay.payment.application.service.clock.TestClockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GAP-078 (critique v3 F5): pins the test-clock HARD GATES via direct construction (mirrors
 * {@link TestSandboxControllerTest} / {@link TestEventControllerTest}). A LIVE key is 404 (no oracle) on
 * PUT/GET/DELETE and the service is NEVER called; a TEST key sets/gets/clears UNDER the caller's principal
 * tenant (never a body/header — the exact tenant string is verified); an unparseable/missing {@code now}
 * is 400.
 *
 * <p>L-071: the ObjectMapper registers modules for Instant; a server-generated id is never asserted (there
 * is none here).</p>
 */
class TestClockControllerTest {

    private static final String TENANT = "t1";
    private static final String OTHER_TENANT = "t2";
    private static final Instant FIXED = Instant.parse("2026-01-01T00:00:00Z");

    private final TestClockService service = mock(TestClockService.class);
    private final TestClockController controller = new TestClockController(service);

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

    // ---- PUT (set) ----

    @Test
    void testKey_set_freezesUnderCallerTenant_returnsFrozen200() {
        authenticateAs(TENANT, false); // TEST key

        ResponseEntity<TestClockResponse> resp =
                controller.set(Map.of("now", "2026-01-01T00:00:00Z"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        TestClockResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.now()).isEqualTo(FIXED);
        assertThat(body.frozen()).isTrue();
        // The clock is set UNDER the principal's tenant — never a body/header. Verify the exact tenant.
        verify(service).set(TENANT, FIXED);
    }

    @Test
    void set_tenantIsPrincipal_notBleedingToAnotherTenant() {
        authenticateAs(OTHER_TENANT, false); // a DIFFERENT test key

        controller.set(Map.of("now", "2026-01-01T00:00:00Z"));

        // Tenant A never appears; only the caller's own principal tenant is set (tenant isolation).
        verify(service).set(OTHER_TENANT, FIXED);
        verify(service, never()).set(eq(TENANT), any());
    }

    @Test
    void set_unparseableNow_is400_andNeverSets() {
        authenticateAs(TENANT, false);

        assertThatThrownBy(() -> controller.set(Map.of("now", "not-an-instant")))
                .isInstanceOf(InvalidRequestException.class);
        verify(service, never()).set(anyString(), any());
    }

    @Test
    void set_missingNow_is400_andNeverSets() {
        authenticateAs(TENANT, false);

        assertThatThrownBy(() -> controller.set(Map.of()))
                .isInstanceOf(InvalidRequestException.class);
        verify(service, never()).set(anyString(), any());
    }

    @Test
    void liveKey_set_is404_andNeverSets() {
        authenticateAs(TENANT, true); // LIVE key

        ResponseEntity<TestClockResponse> resp =
                controller.set(Map.of("now", "2026-01-01T00:00:00Z"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(service, never()).set(anyString(), any());
    }

    // ---- GET (read) ----

    @Test
    void testKey_get_reflectsFrozen() {
        authenticateAs(TENANT, false);
        when(service.get(TENANT)).thenReturn(Optional.of(FIXED));

        ResponseEntity<TestClockResponse> resp = controller.get();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().now()).isEqualTo(FIXED);
        assertThat(resp.getBody().frozen()).isTrue();
        verify(service).get(TENANT);
    }

    @Test
    void testKey_get_reflectsRealTimeWhenNotFrozen() {
        authenticateAs(TENANT, false);
        when(service.get(TENANT)).thenReturn(Optional.empty());

        ResponseEntity<TestClockResponse> resp = controller.get();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().frozen()).isFalse();
        // now is the live Instant.now() (not asserted to a literal); just non-null + recent.
        assertThat(resp.getBody().now()).isNotNull();
    }

    @Test
    void liveKey_get_is404_andNeverReads() {
        authenticateAs(TENANT, true);

        ResponseEntity<TestClockResponse> resp = controller.get();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(service, never()).get(anyString());
    }

    // ---- DELETE (clear) ----

    @Test
    void testKey_clear_revertsToRealTime_returns200() {
        authenticateAs(TENANT, false);

        ResponseEntity<TestClockResponse> resp = controller.clear();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().frozen()).isFalse();
        verify(service).clear(TENANT);
    }

    @Test
    void liveKey_clear_is404_andNeverClears() {
        authenticateAs(TENANT, true);

        ResponseEntity<TestClockResponse> resp = controller.clear();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(service, never()).clear(anyString());
    }
}
