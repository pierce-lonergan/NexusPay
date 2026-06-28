package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.gateway.adapter.in.rest.dto.RefundApiResponse;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.application.service.projection.PaymentProjectionQueryService;
import io.nexuspay.payment.domain.RefundResponse;
import io.nexuspay.payment.domain.projection.RefundProjectionRow;
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
 * GAP-076 (critique v3 F1): GET /v1/refunds list endpoint (direct-construction controller test).
 *
 * <ul>
 *   <li>a refund is listable filtered by the {@code payment} param (parent payment_id);</li>
 *   <li>tenant + livemode come from the principal (a test key sees only its own test refunds);</li>
 *   <li>pagination params are forwarded (the query service clamps).</li>
 * </ul>
 */
class RefundListEndpointTest {

    private PaymentProjectionQueryService projectionQuery;
    private RefundController controller;

    private static final String TENANT_A = "tenant-A";

    private final NexusPayPrincipal testKeyA =
            new NexusPayPrincipal("op_A", TENANT_A, "operator", NexusPayPrincipal.AuthMethod.API_KEY, null, false);

    @BeforeEach
    void setUp() {
        var gateway = mock(PaymentGatewayPort.class);
        projectionQuery = mock(PaymentProjectionQueryService.class);
        controller = new RefundController(gateway, projectionQuery);
    }

    private static RefundProjectionRow row(String id, String paymentId, String status) {
        return new RefundProjectionRow(id, paymentId, TENANT_A, false, status, 2500, "USD",
                "requested_by_customer", "mock", null, null, Instant.now(), Instant.now());
    }

    @Test
    void listRefunds_filteredByPayment_isListable() {
        when(projectionQuery.listRefunds(eq(TENANT_A), eq(false), eq("pay_1"), any(), anyInt(), anyInt()))
                .thenReturn(List.of(row("re_1", "pay_1", RefundResponse.STATUS_SUCCEEDED)));

        ResponseEntity<List<RefundApiResponse>> resp =
                controller.listRefunds("pay_1", null, 20, 0, testKeyA);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        RefundApiResponse r = resp.getBody().get(0);
        assertThat(r.id()).isEqualTo("re_1");
        assertThat(r.payment_id()).isEqualTo("pay_1");
        assertThat(r.status()).isEqualTo(RefundResponse.STATUS_SUCCEEDED);
        assertThat(r.requires_approval()).isFalse(); // a listed refund is self-describing as not-approval
    }

    @Test
    void listRefunds_tenantAndLivemodeFromPrincipal_paginationForwarded() {
        when(projectionQuery.listRefunds(any(), anyBoolean(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        controller.listRefunds(null, "succeeded", 50, 5, testKeyA);

        ArgumentCaptor<String> tenant = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Boolean> live = ArgumentCaptor.forClass(Boolean.class);
        verify(projectionQuery).listRefunds(tenant.capture(), live.capture(),
                eq((String) null), eq("succeeded"), eq(50), eq(5));
        assertThat(tenant.getValue()).isEqualTo(TENANT_A);
        assertThat(live.getValue()).isFalse(); // test key -> only test refunds
    }
}
