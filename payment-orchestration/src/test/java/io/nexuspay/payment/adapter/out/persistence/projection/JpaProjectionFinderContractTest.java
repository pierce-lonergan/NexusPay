package io.nexuspay.payment.adapter.out.persistence.projection;

import io.nexuspay.payment.domain.projection.RefundProjectionRow;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GAP-076 (critique v3 F1): no-Docker safety net for the tenant/livemode IDOR guarantee + the
 * OffsetLimitRequest offset math.
 *
 * <p>The behavioral SQL proof (cross-tenant no-leak, livemode scoping, offset rows 5..14) lives in the
 * Testcontainers {@code PaymentProjectionReadModelIntegrationTest}, which SKIPS when Docker is absent.
 * This class runs ALWAYS and locks the two things that previously rested on un-executed method names:</p>
 *
 * <ul>
 *   <li><b>Finder names are pinned</b> (reflection): a rename that drops the {@code TenantId}/{@code Livemode}
 *       scope from a derived finder fails to resolve the method and reds this test — mirroring
 *       {@code JpaCustomerRepositoryAdapterTest.softDeleteFinderNameIsTheExpectedDerivedQuery}.</li>
 *   <li><b>The adapter delegates to the SCOPED finder with the tenant+livemode args</b> (Mockito verify):
 *       a dropped scope arg is caught here, not only behind a Docker gate.</li>
 *   <li><b>The Pageable passed to the finder reports getOffset()==offset</b> (the offset-math fix): the old
 *       {@code PageRequest.of(offset/limit, limit)} reported {@code getOffset()==0} for offset=5,limit=10.</li>
 * </ul>
 */
class JpaProjectionFinderContractTest {

    // ---- finder-name pins (a dropped scope arg fails to resolve -> red) ----

    @Test
    void paymentFinderNames_carryTenantAndLivemodeScope() throws NoSuchMethodException {
        Class<?> repo = JpaPaymentProjectionRepositoryAdapter.JpaPaymentProjectionRepo.class;
        Method base = repo.getMethod("findByTenantIdAndLivemodeOrderByCreatedAtDesc",
                String.class, boolean.class, Pageable.class);
        Method status = repo.getMethod("findByTenantIdAndLivemodeAndStatusOrderByCreatedAtDesc",
                String.class, boolean.class, String.class, Pageable.class);
        Method customer = repo.getMethod("findByTenantIdAndLivemodeAndCustomerIdOrderByCreatedAtDesc",
                String.class, boolean.class, String.class, Pageable.class);
        Method both = repo.getMethod("findByTenantIdAndLivemodeAndStatusAndCustomerIdOrderByCreatedAtDesc",
                String.class, boolean.class, String.class, String.class, Pageable.class);
        for (Method m : List.of(base, status, customer, both)) {
            assertThat(m.getName()).contains("TenantId").contains("Livemode");
        }
    }

    @Test
    void refundFinderNames_carryTenantAndLivemodeScope() throws NoSuchMethodException {
        Class<?> repo = JpaRefundProjectionRepositoryAdapter.JpaRefundProjectionRepo.class;
        Method base = repo.getMethod("findByTenantIdAndLivemodeOrderByCreatedAtDesc",
                String.class, boolean.class, Pageable.class);
        Method status = repo.getMethod("findByTenantIdAndLivemodeAndStatusOrderByCreatedAtDesc",
                String.class, boolean.class, String.class, Pageable.class);
        Method payment = repo.getMethod("findByTenantIdAndLivemodeAndPaymentIdOrderByCreatedAtDesc",
                String.class, boolean.class, String.class, Pageable.class);
        Method both = repo.getMethod("findByTenantIdAndLivemodeAndPaymentIdAndStatusOrderByCreatedAtDesc",
                String.class, boolean.class, String.class, String.class, Pageable.class);
        for (Method m : List.of(base, status, payment, both)) {
            assertThat(m.getName()).contains("TenantId").contains("Livemode");
        }
    }

    // ---- the adapter calls the tenant+livemode-scoped finder with the caller's args, and a correct offset ----

    @Test
    void paymentAdapter_delegatesToScopedFinder_withTenantLivemode_andHonoredOffset() {
        var jpa = mock(JpaPaymentProjectionRepositoryAdapter.JpaPaymentProjectionRepo.class);
        when(jpa.findByTenantIdAndLivemodeOrderByCreatedAtDesc(any(), eq(false), any())).thenReturn(List.of());
        var adapter = new JpaPaymentProjectionRepositoryAdapter(jpa);

        adapter.listByTenant("tenant-B", false, null, null, 10, 5);

        org.mockito.ArgumentCaptor<Pageable> page = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        // the UNFILTERED case must hit the (tenant_id, livemode) finder with tenant-B + false (no leak).
        verify(jpa).findByTenantIdAndLivemodeOrderByCreatedAtDesc(eq("tenant-B"), eq(false), page.capture());
        // ABSOLUTE offset honored: getOffset()==5 (the old PageRequest.of(5/10,10) would have reported 0).
        assertThat(page.getValue().getOffset()).isEqualTo(5);
        assertThat(page.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    void paymentAdapter_statusAndCustomerFilter_hitsTheFourArgScopedFinder() {
        var jpa = mock(JpaPaymentProjectionRepositoryAdapter.JpaPaymentProjectionRepo.class);
        when(jpa.findByTenantIdAndLivemodeAndStatusAndCustomerIdOrderByCreatedAtDesc(
                any(), eq(false), any(), any(), any())).thenReturn(List.of());
        var adapter = new JpaPaymentProjectionRepositoryAdapter(jpa);

        adapter.listByTenant("tenant-A", false, "succeeded", "cus_9", 20, 0);

        verify(jpa).findByTenantIdAndLivemodeAndStatusAndCustomerIdOrderByCreatedAtDesc(
                eq("tenant-A"), eq(false), eq("succeeded"), eq("cus_9"), any());
    }

    @Test
    void refundAdapter_paymentFilter_hitsTheScopedPaymentFinder_withHonoredOffset() {
        var jpa = mock(JpaRefundProjectionRepositoryAdapter.JpaRefundProjectionRepo.class);
        when(jpa.findByTenantIdAndLivemodeAndPaymentIdOrderByCreatedAtDesc(any(), eq(false), any(), any()))
                .thenReturn(List.of(refundEntity()));
        var adapter = new JpaRefundProjectionRepositoryAdapter(jpa);

        var rows = adapter.listByTenant("tenant-A", false, "pay_1", null, 7, 14);

        org.mockito.ArgumentCaptor<Pageable> page = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(jpa).findByTenantIdAndLivemodeAndPaymentIdOrderByCreatedAtDesc(
                eq("tenant-A"), eq(false), eq("pay_1"), page.capture());
        assertThat(page.getValue().getOffset()).isEqualTo(14);
        assertThat(page.getValue().getPageSize()).isEqualTo(7);
        assertThat(rows).extracting(RefundProjectionRow::paymentId).containsExactly("pay_1");
    }

    private static RefundProjectionEntity refundEntity() {
        RefundProjectionEntity e = new RefundProjectionEntity();
        e.setRefundId("re_1");
        e.setPaymentId("pay_1");
        e.setTenantId("tenant-A");
        e.setLivemode(false);
        e.setStatus("succeeded");
        e.setAmount(2500);
        e.setCurrency("USD");
        e.setCreatedAt(Instant.EPOCH);
        e.setUpdatedAt(Instant.EPOCH);
        return e;
    }
}
