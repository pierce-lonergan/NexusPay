package io.nexuspay.payment.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.exception.InvalidRequestException;
import io.nexuspay.common.tenant.LiveModePrincipal;
import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.payment.application.service.paymentmethod.PaymentMethodService;
import io.nexuspay.payment.domain.paymentmethod.PaymentMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TEST-3b: pins {@code PaymentMethodController}'s tenant + livemode derivation, the PAN-reject guard, and
 * the no-tenant/no-credential-in-body guarantee, via direct controller instantiation with a mocked
 * service (no Spring). Mirrors {@code CustomerControllerTenantLivemodeTest}.
 */
class PaymentMethodControllerTenantLivemodeTest {

    private static final String TENANT = "t1";
    private static final String CUS = "cus_1";

    private final PaymentMethodService service = mock(PaymentMethodService.class);
    private final PaymentMethodController controller = new PaymentMethodController(service);

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

    private PaymentMethod pm(boolean livemode) {
        return PaymentMethod.create(TENANT, CUS, livemode, "card",
                "visa", "4242", 12, 2034, "credit", "pmref_test_pm_card_visa", null);
    }

    private static PaymentMethodController.AttachPaymentMethodRequest body(Map<String, Object> metadata) {
        return new PaymentMethodController.AttachPaymentMethodRequest(
                "card", "pm_card_visa", null, null, null, null, null, metadata);
    }

    @Test
    void attachUnderTestKey_stampsLivemodeFalse_and201() {
        authenticateAs(TENANT, false); // TEST key
        when(service.attach(eq(TENANT), eq(CUS), eq(false), eq(true),
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(pm(false));

        ResponseEntity<PaymentMethodController.PaymentMethodResponse> resp =
                controller.attachPaymentMethod(CUS, body(null));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // livemode=false + isTest=true derived from CallerMode, never the body.
        verify(service).attach(eq(TENANT), eq(CUS), eq(false), eq(true),
                eq("card"), eq("pm_card_visa"), any(), any(), any(), any(), any(), any());
        PaymentMethodController.PaymentMethodResponse out = resp.getBody();
        assertThat(out).isNotNull();
        assertThat(out.id()).startsWith("pm_");
        assertThat(out.object()).isEqualTo("payment_method");
        assertThat(out.livemode()).isFalse();
        assertThat(out.customer()).isEqualTo(CUS);
    }

    @Test
    void attachUnderLiveKey_stampsLivemodeTrue() {
        authenticateAs(TENANT, true); // LIVE key
        when(service.attach(eq(TENANT), eq(CUS), eq(true), eq(false),
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(pm(true));

        ResponseEntity<PaymentMethodController.PaymentMethodResponse> resp =
                controller.attachPaymentMethod(CUS,
                        new PaymentMethodController.AttachPaymentMethodRequest(
                                "card", "ptok_live_x", "visa", "1111", 1, 2030, "credit", null));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(service).attach(eq(TENANT), eq(CUS), eq(true), eq(false),
                any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().livemode()).isTrue();
    }

    @Test
    void retrieveReturns404WhenServiceEmpty_noOracle() {
        authenticateAs(TENANT, false);
        when(service.findById(eq("pm_victim"), eq(TENANT))).thenReturn(java.util.Optional.empty());

        ResponseEntity<PaymentMethodController.PaymentMethodResponse> resp =
                controller.getPaymentMethod("pm_victim");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(service).findById("pm_victim", TENANT);
    }

    // ---- (g) PAN-like body field -> 400, service never called ----

    @Test
    void attachWithPanLikeMetadataField_throws400_serviceNeverCalled() {
        authenticateAs(TENANT, false);

        Map<String, Object> dirtyMeta = new LinkedHashMap<>();
        // smuggled raw PAN under a card-material metadata KEY (value built programmatically — never a literal
        // card number). The metadata-key guard rejects on the "number" key regardless of value shape.
        dirtyMeta.put("number", "4".repeat(16));

        assertThatThrownBy(() -> controller.attachPaymentMethod(CUS, body(dirtyMeta)))
                .isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(service);
    }

    @Test
    void attachWithCvcAndCardKeys_throws400_serviceNeverCalled() {
        authenticateAs(TENANT, false);
        Map<String, Object> dirty = new LinkedHashMap<>();
        dirty.put("cvc", "123");
        assertThatThrownBy(() -> controller.attachPaymentMethod(CUS, body(dirty)))
                .isInstanceOf(InvalidRequestException.class);

        Map<String, Object> dirty2 = new LinkedHashMap<>();
        dirty2.put("card", Map.of("note", "x"));
        assertThatThrownBy(() -> controller.attachPaymentMethod(CUS, body(dirty2)))
                .isInstanceOf(InvalidRequestException.class);

        verify(service, never()).attach(any(), any(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ---- response body NEVER contains tenant OR credential_ref (L-071 serialization) ----

    @Test
    void responseBodyNeverContainsTenantOrCredentialRef() throws Exception {
        authenticateAs(TENANT, false);
        when(service.attach(eq(TENANT), eq(CUS), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(pm(false));

        ResponseEntity<PaymentMethodController.PaymentMethodResponse> resp =
                controller.attachPaymentMethod(CUS, body(null));

        // L-071: PaymentMethod has an Instant-derived `created` + a metadata Map -> findAndRegisterModules.
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String json = mapper.writeValueAsString(resp.getBody());

        assertThat(json).doesNotContain("tenant");
        assertThat(json).doesNotContain(TENANT);
        assertThat(json).doesNotContain("credential");
        assertThat(json).doesNotContain("pmref_test_pm_card_visa");
        // sanity: it DID serialize the method fields
        assertThat(json).contains("\"object\":\"payment_method\"");
        assertThat(json).contains("pm_");
        assertThat(json).contains("4242");
    }
}
