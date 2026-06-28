package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.gateway.adapter.in.rest.dto.PaymentApiResponse;
import io.nexuspay.gateway.application.RefundOrchestrationService;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.application.screening.ScreeningOriginService;
import io.nexuspay.payment.application.service.OffSessionChargeService;
import io.nexuspay.payment.application.service.projection.PaymentProjectionQueryService;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.projection.PaymentProjectionRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GAP-076 (critique v3 F1): GET /v1/payments list endpoint (direct-construction controller test, the
 * gateway-api convention — see PaymentControllerOwnershipTest).
 *
 * <ul>
 *   <li>read-your-write SHAPE: a created test-mode payment row lists with its status + mode/livemode
 *       derived from the row (L-072 snake_case wire keys via the DTO);</li>
 *   <li>a forced-decline row lists with status=failed + error_code;</li>
 *   <li>a requires_action/processing row lists in that non-terminal state;</li>
 *   <li>tenant + livemode are taken from the PRINCIPAL (a test key -> livemode=false), never a client param;</li>
 *   <li>an over-cap limit is passed to the query service (which clamps it).</li>
 * </ul>
 *
 * <p>L-071: the {@link PaymentProjectionRow} carries an {@link Instant} {@code createdAt}; no hardcoded
 * server-generated id is asserted.</p>
 */
class PaymentListEndpointTest {

    private PaymentProjectionQueryService projectionQuery;
    private PaymentController controller;

    private static final String TENANT_A = "tenant-A";

    /** A test key principal: live()==false (an sk_test_ key). */
    private final NexusPayPrincipal testKeyA =
            new NexusPayPrincipal("op_A", TENANT_A, "operator", NexusPayPrincipal.AuthMethod.API_KEY, null, false);

    @BeforeEach
    void setUp() {
        var gateway = mock(PaymentGatewayPort.class);
        var refundOrchestration = mock(RefundOrchestrationService.class);
        var screeningOrigins = mock(ScreeningOriginService.class);
        var offSession = mock(OffSessionChargeService.class);
        projectionQuery = mock(PaymentProjectionQueryService.class);
        controller = new PaymentController(gateway, refundOrchestration, screeningOrigins, offSession,
                projectionQuery);
    }

    private static PaymentProjectionRow row(String id, String status, String errorCode, String customer) {
        return new PaymentProjectionRow(id, TENANT_A, false, status, 5000, "USD", "automatic", customer,
                "mock", errorCode, errorCode == null ? null : "decline message", Instant.now(), Instant.now());
    }

    @Test
    void listPayments_createdTestPayment_isListableWithStatus_andTestMode() {
        when(projectionQuery.listPayments(eq(TENANT_A), eq(false), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(row("pay_test_1", PaymentResponse.STATUS_SUCCEEDED, null, "cus_1")));

        ResponseEntity<List<PaymentApiResponse>> resp =
                controller.listPayments(null, null, 20, 0, testKeyA);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        PaymentApiResponse p = resp.getBody().get(0);
        assertThat(p.id()).isEqualTo("pay_test_1");
        assertThat(p.status()).isEqualTo(PaymentResponse.STATUS_SUCCEEDED);
        assertThat(p.mode()).isEqualTo("test");       // derived from the row's livemode=false
        assertThat(p.livemode()).isFalse();
    }

    @Test
    void listPayments_forcedDecline_listsFailedWithErrorCode() {
        when(projectionQuery.listPayments(any(), anyBoolean(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(row("pay_test_2", PaymentResponse.STATUS_FAILED, "card_declined", "cus_1")));

        var body = controller.listPayments(null, null, 20, 0, testKeyA).getBody();

        assertThat(body.get(0).status()).isEqualTo(PaymentResponse.STATUS_FAILED);
        assertThat(body.get(0).error_code()).isEqualTo("card_declined");
    }

    @Test
    void listPayments_nonTerminalRequiresActionOrProcessing_isListable() {
        when(projectionQuery.listPayments(any(), anyBoolean(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(
                        row("pay_test_3", PaymentResponse.STATUS_REQUIRES_ACTION, null, "cus_1"),
                        row("pay_test_4", PaymentResponse.STATUS_PROCESSING, null, "cus_1")));

        var body = controller.listPayments(null, null, 20, 0, testKeyA).getBody();

        assertThat(body).extracting(PaymentApiResponse::status)
                .containsExactly(PaymentResponse.STATUS_REQUIRES_ACTION, PaymentResponse.STATUS_PROCESSING);
    }

    @Test
    void listPayments_tenantAndLivemodeFromPrincipal_filtersForwarded_overCapPassedToClamp() {
        when(projectionQuery.listPayments(any(), anyBoolean(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        controller.listPayments("succeeded", "cus_9", 5000, 0, testKeyA);

        ArgumentCaptor<String> tenant = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Boolean> live = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(projectionQuery).listPayments(tenant.capture(), live.capture(),
                eq("succeeded"), eq("cus_9"), limit.capture(), eq(0));
        assertThat(tenant.getValue()).isEqualTo(TENANT_A);  // from the principal, never a client param
        assertThat(live.getValue()).isFalse();              // test key -> livemode=false
        assertThat(limit.getValue()).isEqualTo(5000);       // passed through; the QUERY SERVICE clamps it
    }
}
