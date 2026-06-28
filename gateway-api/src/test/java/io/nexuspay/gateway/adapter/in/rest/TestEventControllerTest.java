package io.nexuspay.gateway.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.event.EventTypes;
import io.nexuspay.common.exception.InvalidRequestException;
import io.nexuspay.common.tenant.LiveModePrincipal;
import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.gateway.adapter.in.rest.dto.TestEventResponse;
import io.nexuspay.gateway.adapter.out.webhook.TestEventOutboxAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TEST-4a (D1): pins the test-event trigger's HARD GATES via direct construction (mirrors
 * DisputeTestControllerTest). A LIVE key is 404 (no oracle) and never synthesizes; a TEST key synthesizes
 * UNDER the caller tenant with the dotted→internal mapping correct and livemode=false; an UNKNOWN type is
 * 400 and never synthesizes; the tenant is always the principal's, never the body.
 *
 * <p>L-071: any ObjectMapper here registers modules for Instant; the server-minted event id is never
 * asserted literally (only its prefix / non-blankness).</p>
 */
class TestEventControllerTest {

    private static final String TENANT = "t1";

    private final TestEventOutboxAdapter outbox = mock(TestEventOutboxAdapter.class);
    private final TestEventController controller = new TestEventController(outbox);

    // L-071: Instant round-trips need modules registered (EventEnvelope.timestamp is Instant). Not used to
    // assert ids, but kept honest for any envelope serialization in helpers.
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
    void testKey_triggersPaymentSucceeded_underCallerTenant_livemodeFalse_correctInternalMapping() {
        authenticateAs(TENANT, false); // TEST key
        when(outbox.synthesize(eq(TENANT), anyString(), anyString(), anyString(), any(), eq(false)))
                .thenReturn("evt_generated123");

        ResponseEntity<TestEventResponse> resp =
                controller.trigger(Map.of("type", "payment.succeeded"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        TestEventResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.type()).isEqualTo("payment.succeeded");
        assertThat(body.livemode()).isFalse();
        // L-071: never assert the literal server-minted id — only prefix/non-blank.
        assertThat(body.id()).startsWith("evt_").isNotBlank();

        // Synthesized UNDER the caller tenant, with the dotted->internal mapping (PaymentCaptured) +
        // aggregate type (Payment) + livemode=false.
        ArgumentCaptor<String> internalCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> aggCap = ArgumentCaptor.forClass(String.class);
        verify(outbox).synthesize(eq(TENANT), internalCap.capture(), aggCap.capture(),
                anyString(), any(), eq(false));
        assertThat(internalCap.getValue()).isEqualTo(EventTypes.PAYMENT_CAPTURED);
        assertThat(aggCap.getValue()).isEqualTo(EventTypes.AGGREGATE_PAYMENT);
    }

    @Test
    void liveKey_is404_andNeverSynthesizes() {
        authenticateAs(TENANT, true); // LIVE key

        ResponseEntity<TestEventResponse> resp =
                controller.trigger(Map.of("type", "payment.succeeded"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(outbox, never()).synthesize(anyString(), anyString(), anyString(), anyString(),
                any(), anyBoolean());
    }

    @Test
    void unknownType_is400_andNeverSynthesizes() {
        authenticateAs(TENANT, false); // TEST key

        assertThatThrownBy(() -> controller.trigger(Map.of("type", "not.a.type")))
                .isInstanceOf(InvalidRequestException.class);
        verify(outbox, never()).synthesize(anyString(), anyString(), anyString(), anyString(),
                any(), anyBoolean());
    }

    @Test
    void wildcardType_is400_andNeverSynthesizes() {
        authenticateAs(TENANT, false); // TEST key

        assertThatThrownBy(() -> controller.trigger(Map.of("type", "*")))
                .isInstanceOf(InvalidRequestException.class);
        verify(outbox, never()).synthesize(anyString(), anyString(), anyString(), anyString(),
                any(), anyBoolean());
    }

    @Test
    void disputeType_mapsToDisputeInternalAndAggregate_underCallerTenant() {
        authenticateAs(TENANT, false);
        when(outbox.synthesize(eq(TENANT), anyString(), anyString(), anyString(), any(), eq(false)))
                .thenReturn("evt_disp1");

        controller.trigger(Map.of("type", "dispute.created"));

        verify(outbox).synthesize(eq(TENANT), eq(EventTypes.DISPUTE_CREATED),
                eq(EventTypes.AGGREGATE_DISPUTE), anyString(), any(), eq(false));
    }

    @Test
    void dataOverlay_isMergedIntoObject() {
        authenticateAs(TENANT, false);
        when(outbox.synthesize(eq(TENANT), anyString(), anyString(), anyString(), any(), eq(false)))
                .thenReturn("evt_x");

        controller.trigger(Map.of(
                "type", "payment.succeeded",
                "data", Map.of("amount", 7777, "custom_field", "hello")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> objCap = ArgumentCaptor.forClass(Map.class);
        verify(outbox).synthesize(eq(TENANT), anyString(), anyString(), anyString(),
                objCap.capture(), eq(false));
        Map<String, Object> object = objCap.getValue();
        assertThat(object.get("amount")).isEqualTo(7777);
        assertThat(object.get("custom_field")).isEqualTo("hello");
    }
}
