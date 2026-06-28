package io.nexuspay.payment.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.tenant.LiveModePrincipal;
import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.payment.application.service.mandate.MandateService;
import io.nexuspay.payment.domain.mandate.Mandate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TEST-3d: pins {@code MandateController}'s tenant + livemode derivation and the no-tenant-in-body
 * guarantee, via direct controller instantiation with a mocked service (no Spring). Mirrors
 * {@code PaymentMethodControllerTenantLivemodeTest}.
 */
class MandateControllerTenantLivemodeTest {

    private static final String TENANT = "t1";
    private static final String CUS = "cus_1";
    private static final String PM = "pm_1";

    private final MandateService service = mock(MandateService.class);
    private final MandateController controller = new MandateController(service);

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

    private Mandate mandate(boolean livemode) {
        Mandate m = Mandate.create(TENANT, CUS, PM, livemode, Mandate.TYPE_MULTI_USE, "recurring", null);
        return m;
    }

    private static MandateController.CreateMandateRequest body() {
        return new MandateController.CreateMandateRequest(PM, null, "recurring", null);
    }

    @Test
    void createUnderTestKey_stampsLivemodeFalse_isTestTrue_and201() {
        authenticateAs(TENANT, false); // TEST key
        when(service.create(eq(TENANT), eq(false), eq(true), eq(PM), any(), any(), any()))
                .thenReturn(mandate(false));

        ResponseEntity<MandateController.MandateResponse> resp =
                controller.createMandate(body());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // livemode=false + isTest=true derived from CallerMode, never the body.
        verify(service).create(eq(TENANT), eq(false), eq(true), eq(PM), any(), eq("recurring"), any());
        MandateController.MandateResponse out = resp.getBody();
        assertThat(out).isNotNull();
        assertThat(out.id()).startsWith("mandate_");
        assertThat(out.object()).isEqualTo("mandate");
        assertThat(out.status()).isEqualTo(Mandate.STATUS_ACTIVE);
        assertThat(out.livemode()).isFalse();
        assertThat(out.customer()).isEqualTo(CUS);
        assertThat(out.paymentMethod()).isEqualTo(PM);
    }

    @Test
    void createUnderLiveKey_stampsLivemodeTrue() {
        authenticateAs(TENANT, true); // LIVE key
        when(service.create(eq(TENANT), eq(true), eq(false), eq(PM), any(), any(), any()))
                .thenReturn(mandate(true));

        ResponseEntity<MandateController.MandateResponse> resp =
                controller.createMandate(body());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(service).create(eq(TENANT), eq(true), eq(false), eq(PM), any(), any(), any());
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().livemode()).isTrue();
    }

    @Test
    void retrieveReturns404WhenServiceEmpty_noOracle() {
        authenticateAs(TENANT, false);
        when(service.findById(eq("mandate_victim"), eq(TENANT))).thenReturn(java.util.Optional.empty());

        ResponseEntity<MandateController.MandateResponse> resp =
                controller.getMandate("mandate_victim");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(service).findById("mandate_victim", TENANT);
    }

    @Test
    void revokeReturnsInactiveBody() {
        authenticateAs(TENANT, false);
        Mandate revoked = mandate(false);
        revoked.revoke();
        when(service.revoke(eq("mandate_1"), eq(TENANT))).thenReturn(revoked);

        ResponseEntity<MandateController.MandateResponse> resp = controller.revokeMandate("mandate_1");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().status()).isEqualTo(Mandate.STATUS_INACTIVE);
        verify(service).revoke("mandate_1", TENANT);
    }

    // ---- response body NEVER contains tenant (L-071 serialization) ----

    @Test
    void responseBodyNeverContainsTenant() throws Exception {
        authenticateAs(TENANT, false);
        when(service.create(eq(TENANT), anyBoolean(), anyBoolean(), eq(PM), any(), any(), any()))
                .thenReturn(mandate(false));

        ResponseEntity<MandateController.MandateResponse> resp =
                controller.createMandate(body());

        // L-071: Mandate has an Instant-derived `created` -> findAndRegisterModules.
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String json = mapper.writeValueAsString(resp.getBody());

        assertThat(json).doesNotContain("tenant");
        assertThat(json).doesNotContain(TENANT);
        // sanity: it DID serialize the mandate fields with the snake_case payment_method key
        assertThat(json).contains("\"object\":\"mandate\"");
        assertThat(json).contains("mandate_");
        assertThat(json).contains("\"payment_method\":\"" + PM + "\"");
        assertThat(json).contains(CUS);
    }
}
