package io.nexuspay.dispute.adapter.in.rest;

import io.nexuspay.common.tenant.LiveModePrincipal;
import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.dispute.application.service.DisputeLifecycleService;
import io.nexuspay.dispute.domain.Dispute;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TEST-2: pins the test-mode dispute simulator's HARD GATES.
 *
 * <ul>
 *   <li>A LIVE key is UNREACHABLE — 404, and openDispute is NEVER called (no oracle, no side effect).</li>
 *   <li>A TEST key opens the dispute UNDER THE CALLER'S TENANT with livemode=false.</li>
 *   <li>A non-test payment id (not {@code pay_test_*}) is rejected 400 (cannot aim at a real payment).</li>
 * </ul>
 */
class DisputeTestControllerTest {

    private static final String TENANT = "t1";

    private final DisputeLifecycleService lifecycle = mock(DisputeLifecycleService.class);
    private final DisputeTestController controller = new DisputeTestController(lifecycle);

    /** A minimal common-portable principal carrying tenant + live mode (no iam dependency). */
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

    private Dispute opened() {
        Dispute d = new Dispute();
        d.setId("dp_1");
        d.setTenantId(TENANT);
        d.setPaymentId("pay_test_abc");
        d.setExternalDisputeId("test_dp_x");
        d.setAmount(5000L);
        d.setCurrency("USD");
        d.setStatus(io.nexuspay.dispute.domain.DisputeState.OPENED);
        d.setNetwork("test");
        d.setReasonCode("fraudulent");
        d.setCreatedAt(java.time.Instant.now());
        return d;
    }

    @Test
    void liveKey_is404_andNeverOpensDispute() {
        authenticateAs(TENANT, true); // LIVE key

        ResponseEntity<?> resp = controller.simulateDispute(Map.of("payment_id", "pay_test_abc"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(lifecycle, never()).openDispute(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyLong(), anyString(), anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void testKey_opensDisputeUnderCallerTenant_livemodeFalse() {
        authenticateAs(TENANT, false); // TEST key
        when(lifecycle.openDispute(eq(TENANT), eq("pay_test_abc"), anyString(), anyString(), anyString(),
                anyLong(), anyString(), anyString(), any(), eq(false))).thenReturn(opened());

        ResponseEntity<?> resp = controller.simulateDispute(Map.of(
                "payment_id", "pay_test_abc", "amount", 5000, "currency", "USD", "reason", "10.4"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // opened UNDER the caller tenant, livemode=false (TEST).
        verify(lifecycle).openDispute(eq(TENANT), eq("pay_test_abc"), anyString(), eq("10.4"),
                anyString(), eq(5000L), eq("USD"), eq("test"), any(), eq(false));
    }

    @Test
    void nonTestPaymentId_is400() {
        authenticateAs(TENANT, false); // TEST key

        ResponseEntity<?> resp = controller.simulateDispute(Map.of("payment_id", "pay_live_abc"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(lifecycle, never()).openDispute(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyLong(), anyString(), anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void missingPaymentId_is400() {
        authenticateAs(TENANT, false);

        ResponseEntity<?> resp = controller.simulateDispute(Map.of());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
