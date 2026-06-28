package io.nexuspay.payment.application.service.sandbox;

import io.nexuspay.payment.adapter.out.mock.MockPaymentGatewayPort;
import io.nexuspay.payment.application.port.out.CustomerRepository;
import io.nexuspay.payment.application.port.out.MandateRepository;
import io.nexuspay.payment.application.port.out.PaymentMethodRepository;
import io.nexuspay.payment.application.port.out.PaymentProjectionRepository;
import io.nexuspay.payment.application.port.out.RefundProjectionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GAP-077 (critique v3 F4): the ORCHESTRATION contract of {@link SandboxResetService} — verified against
 * mocked ports (the actual {@code WHERE tenant_id=? AND livemode=false} SQL scoping is proven against a real
 * Postgres in {@code app/SandboxResetIntegrationTest}, and the mock-map confirmed-ids clear in
 * {@code MockPaymentGatewayPortForgetTest}).
 *
 * <p>Pins: (1) the tenant passed to EVERY {@code deleteTestRows} is exactly the principal tenant the service
 * was called with — never a derived/other value; (2) the test ids are collected BEFORE any delete and fed to
 * the mock clear; (3) the FK-safe child→parent delete order; (4) the per-table summary mapping; (5) the mock
 * clear is best-effort (a mock failure does NOT propagate / roll back the DB deletes).</p>
 */
class SandboxResetServiceTest {

    private static final String TENANT = "tenant-A";

    private final PaymentProjectionRepository paymentRepo = mock(PaymentProjectionRepository.class);
    private final RefundProjectionRepository refundRepo = mock(RefundProjectionRepository.class);
    private final CustomerRepository customerRepo = mock(CustomerRepository.class);
    private final PaymentMethodRepository pmRepo = mock(PaymentMethodRepository.class);
    private final MandateRepository mandateRepo = mock(MandateRepository.class);
    private final MockPaymentGatewayPort mockGateway = mock(MockPaymentGatewayPort.class);

    private final SandboxResetService service = new SandboxResetService(
            paymentRepo, refundRepo, customerRepo, pmRepo, mandateRepo, mockGateway);

    @Test
    void reset_deletesEveryTableWithTheExactPrincipalTenant_andMapsCounts() {
        when(paymentRepo.findTestIds(TENANT)).thenReturn(List.of("pay_test_1", "pay_test_2"));
        when(refundRepo.findTestIds(TENANT)).thenReturn(List.of("re_test_1"));
        when(refundRepo.deleteTestRows(TENANT)).thenReturn(1);
        when(paymentRepo.deleteTestRows(TENANT)).thenReturn(2);
        when(mandateRepo.deleteTestRows(TENANT)).thenReturn(3);
        when(pmRepo.deleteTestRows(TENANT)).thenReturn(4);
        when(customerRepo.deleteTestRows(TENANT)).thenReturn(5);

        SandboxResetSummary summary = service.reset(TENANT);

        // Summary maps each table's deleted count exactly.
        assertThat(summary.payments()).isEqualTo(2);
        assertThat(summary.refunds()).isEqualTo(1);
        assertThat(summary.mandates()).isEqualTo(3);
        assertThat(summary.paymentMethods()).isEqualTo(4);
        assertThat(summary.customers()).isEqualTo(5);

        // EVERY delete carried the exact principal tenant (no header/body-derived value, no cross-tenant).
        verify(refundRepo).deleteTestRows(TENANT);
        verify(paymentRepo).deleteTestRows(TENANT);
        verify(mandateRepo).deleteTestRows(TENANT);
        verify(pmRepo).deleteTestRows(TENANT);
        verify(customerRepo).deleteTestRows(TENANT);

        // The confirmed test ids (collected BEFORE the deletes) are forwarded to the mock clear unchanged.
        verify(mockGateway).forgetTestArtifacts(List.of("pay_test_1", "pay_test_2"), List.of("re_test_1"));
    }

    @Test
    void reset_collectsIdsBeforeDeletes_andDeletesChildBeforeParent() {
        when(paymentRepo.findTestIds(TENANT)).thenReturn(List.of());
        when(refundRepo.findTestIds(TENANT)).thenReturn(List.of());

        service.reset(TENANT);

        InOrder order = inOrder(paymentRepo, refundRepo, mandateRepo, pmRepo, customerRepo, mockGateway);
        // Step 1: id collection happens before ANY delete.
        order.verify(paymentRepo).findTestIds(TENANT);
        order.verify(refundRepo).findTestIds(TENANT);
        // Step 2: child -> parent order: refunds, payments, mandates, payment_methods, customers.
        order.verify(refundRepo).deleteTestRows(TENANT);
        order.verify(paymentRepo).deleteTestRows(TENANT);
        order.verify(mandateRepo).deleteTestRows(TENANT);
        order.verify(pmRepo).deleteTestRows(TENANT);
        order.verify(customerRepo).deleteTestRows(TENANT);
        // Step 3: mock clear AFTER the deletes.
        order.verify(mockGateway).forgetTestArtifacts(List.of(), List.of());
    }

    @Test
    void mockClearFailure_isSwallowed_doesNotPropagate() {
        when(paymentRepo.findTestIds(TENANT)).thenReturn(List.of("pay_test_1"));
        when(refundRepo.findTestIds(TENANT)).thenReturn(List.of());
        doThrow(new RuntimeException("mock hiccup"))
                .when(mockGateway).forgetTestArtifacts(List.of("pay_test_1"), List.of());

        // A mock-clear failure must NEVER roll back the committed DB deletes — the service swallows + logs it.
        assertThatCode(() -> service.reset(TENANT)).doesNotThrowAnyException();
        // The DB deletes still ran.
        verify(customerRepo).deleteTestRows(TENANT);
    }
}
