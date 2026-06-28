package io.nexuspay.payment.application.service.projection;

import io.nexuspay.payment.application.port.out.PaymentProjectionRepository;
import io.nexuspay.payment.application.port.out.RefundProjectionRepository;
import io.nexuspay.payment.domain.projection.PaymentProjectionRow;
import io.nexuspay.payment.domain.projection.RefundProjectionRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
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
 * GAP-076 (critique v3 F1): read scoping + clamping for {@link PaymentProjectionQueryService}.
 *
 * <ul>
 *   <li>CROSS-TENANT: tenant B's list never returns tenant A's rows (no IDOR / no count leak). The fake-repo
 *       case below proves the service ROUTES the right (tenant_id, livemode) args; the REAL SQL predicate is
 *       proven against Postgres in {@code app}'s {@code PaymentProjectionReadModelIntegrationTest} (the
 *       behavioral no-leak / livemode / offset proof). The {@code …delegatesToTheTenantAndLivemodeScopedFinder}
 *       case pins the arg-passthrough even when Docker (and thus the IT) is unavailable.</li>
 *   <li>LIVEMODE: a test key (livemode=false) lists only livemode=false rows.</li>
 *   <li>status + customer filters select correctly; listRefunds filters by payment_id.</li>
 *   <li>CLAMP: limit&gt;100 -&gt; 100, limit&lt;=0 -&gt; 1, negative offset -&gt; 0 (passed to the repo).</li>
 * </ul>
 */
class PaymentProjectionQueryServiceTest {

    private PaymentProjectionRepository payments;
    private RefundProjectionRepository refunds;
    private PaymentProjectionQueryService service;

    @BeforeEach
    void setUp() {
        payments = mock(PaymentProjectionRepository.class);
        refunds = mock(RefundProjectionRepository.class);
        service = new PaymentProjectionQueryService(payments, refunds);
    }

    private static PaymentProjectionRow row(String id, String tenant, boolean live, String status,
                                            String customer) {
        return new PaymentProjectionRow(id, tenant, live, status, 5000, "USD", "automatic", customer,
                "stripe", null, null, Instant.EPOCH, Instant.EPOCH);
    }

    // ---- CLAMP (the over-cap / under-floor requirement) ----

    @Test
    void listPayments_clampsLimitTo100_andFloorsToOne_andOffsetToZero() {
        when(payments.listByTenant(any(), anyBoolean(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());

        service.listPayments("t", false, null, null, 5000, -7); // over-cap limit, negative offset
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);
        verify(payments).listByTenant(eq("t"), eq(false), any(), any(), limit.capture(), offset.capture());
        assertThat(limit.getValue()).isEqualTo(100); // clamped down
        assertThat(offset.getValue()).isZero();       // negative floored to 0

        service.listPayments("t", false, null, null, 0, 0); // limit<=0 -> 1
        verify(payments).listByTenant(eq("t"), eq(false), any(), any(), eq(1), eq(0));
    }

    @Test
    void listPayments_passesTenantAndLivemodeThrough() {
        when(payments.listByTenant(any(), anyBoolean(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        service.listPayments("tenant-B", true, "succeeded", "cus_9", 20, 0);
        verify(payments).listByTenant(eq("tenant-B"), eq(true), eq("succeeded"), eq("cus_9"), eq(20), eq(0));
    }

    @Test
    void listPayments_alwaysDelegatesToTheTenantAndLivemodeScopedFinder_neverDropsAScopeArg() {
        // F6 (no-Docker safety net): pin that the service passes the CALLER'S tenant + livemode to the scoped
        // repo finder. A refactor that drops or hard-codes a scope arg is caught here even without the
        // Testcontainers SQL proof (PaymentProjectionReadModelIntegrationTest) running. Behavioral isolation
        // through the real SQL is proven there; this guarantees the args reach it.
        when(payments.listByTenant(any(), anyBoolean(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(refunds.listByTenant(any(), anyBoolean(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        service.listPayments("tenant-B", false, null, null, 20, 0);
        verify(payments).listByTenant(eq("tenant-B"), eq(false), any(), any(), anyInt(), anyInt());

        service.listRefunds("tenant-B", false, "pay_1", null, 20, 0);
        verify(refunds).listByTenant(eq("tenant-B"), eq(false), eq("pay_1"), any(), anyInt(), anyInt());
    }

    // ---- CROSS-TENANT + LIVEMODE scoping via a fake repo (the SQL predicate proxy) ----

    @Test
    void listPayments_isTenantAndLivemodeScoped_noCrossTenantLeak() {
        List<PaymentProjectionRow> all = new ArrayList<>();
        all.add(row("pay_a", "tenant-A", false, "succeeded", "cus_a"));
        all.add(row("pay_b", "tenant-B", false, "succeeded", "cus_b"));
        all.add(row("pay_a_live", "tenant-A", true, "succeeded", "cus_a")); // live row, must be excluded for a test key

        PaymentProjectionRepository fake = fakeRepo(all);
        PaymentProjectionQueryService svc = new PaymentProjectionQueryService(fake, refunds);

        // tenant-B (test key) must NOT see any of tenant-A's rows (no IDOR / no count leak)
        List<PaymentProjectionRow> bRows = svc.listPayments("tenant-B", false, null, null, 20, 0);
        assertThat(bRows).extracting(PaymentProjectionRow::paymentId).containsExactly("pay_b");
        assertThat(bRows).extracting(PaymentProjectionRow::tenantId).containsOnly("tenant-B");

        // tenant-A test key sees ONLY its livemode=false row, not its live one
        List<PaymentProjectionRow> aTest = svc.listPayments("tenant-A", false, null, null, 20, 0);
        assertThat(aTest).extracting(PaymentProjectionRow::paymentId).containsExactly("pay_a");
    }

    @Test
    void listPayments_statusAndCustomerFiltersSelectCorrectly() {
        List<PaymentProjectionRow> all = List.of(
                row("pay_1", "t", false, "succeeded", "cus_1"),
                row("pay_2", "t", false, "failed", "cus_1"),
                row("pay_3", "t", false, "succeeded", "cus_2"));
        PaymentProjectionQueryService svc = new PaymentProjectionQueryService(fakeRepo(all), refunds);

        assertThat(svc.listPayments("t", false, "failed", null, 20, 0))
                .extracting(PaymentProjectionRow::paymentId).containsExactly("pay_2");
        assertThat(svc.listPayments("t", false, "succeeded", "cus_2", 20, 0))
                .extracting(PaymentProjectionRow::paymentId).containsExactly("pay_3");
    }

    @Test
    void listRefunds_filtersByPaymentId_clamped() {
        when(refunds.listByTenant(any(), anyBoolean(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(new RefundProjectionRow("re_1", "pay_1", "t", false, "succeeded",
                        2500, "USD", null, "stripe", null, null, Instant.EPOCH, Instant.EPOCH)));

        List<RefundProjectionRow> result = service.listRefunds("t", false, "pay_1", null, 999, -1);

        verify(refunds).listByTenant(eq("t"), eq(false), eq("pay_1"), any(), eq(100), eq(0));
        assertThat(result).extracting(RefundProjectionRow::paymentId).containsExactly("pay_1");
    }

    /** A fake repo applying the (tenant_id, livemode [, status][, customer_id]) predicate the SQL finder uses. */
    private static PaymentProjectionRepository fakeRepo(List<PaymentProjectionRow> all) {
        return new PaymentProjectionRepository() {
            @Override public void upsert(PaymentProjectionRow row) { }
            @Override public void updateStatusIfExists(String id, String t, String s, boolean l) { }
            @Override public List<String> findTestIds(String tenantId) { return List.of(); }
            @Override public int deleteTestRows(String tenantId) { return 0; }
            @Override public List<PaymentProjectionRow> listByTenant(String tenantId, boolean livemode,
                    String statusFilter, String customerFilter, int limit, int offset) {
                return all.stream()
                        .filter(r -> r.tenantId().equals(tenantId))
                        .filter(r -> r.livemode() == livemode)
                        .filter(r -> statusFilter == null || statusFilter.equals(r.status()))
                        .filter(r -> customerFilter == null || customerFilter.equals(r.customerId()))
                        .skip(offset).limit(limit).toList();
            }
        };
    }
}
