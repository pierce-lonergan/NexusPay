package io.nexuspay.payment.application.service.projection;

import io.nexuspay.payment.application.port.out.PaymentProjectionRepository;
import io.nexuspay.payment.application.port.out.RefundProjectionRepository;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.RefundResponse;
import io.nexuspay.payment.domain.projection.PaymentProjectionRow;
import io.nexuspay.payment.domain.projection.ProjectionStatusPrecedence;
import io.nexuspay.payment.domain.projection.RefundProjectionRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * GAP-076 (critique v3 F1): unit tests for {@link PaymentProjectionService} (the BEST-EFFORT writer) and
 * the {@link ProjectionStatusPrecedence} monotonic-rank guard.
 *
 * <ul>
 *   <li>{@code record} builds a row carrying the response fields + the server-derived tenant/livemode and
 *       upserts it; {@code created_at} comes from the response (not now()).</li>
 *   <li>A repository upsert that THROWS is swallowed (the try/catch lives in the service).</li>
 *   <li>Status precedence: processing then succeeded advances; a late processing after succeeded does NOT
 *       regress; pending -> succeeded advances; an out-of-order terminal-then-nonterminal is rejected.</li>
 * </ul>
 *
 * <p>The precedence guard lives in the repository adapter, so here it is asserted directly via
 * {@link ProjectionStatusPrecedence} (the same decision function the adapter calls). The service test uses
 * a fake in-memory repository to prove the idempotent-by-PK + no-regression behavior end-to-end.</p>
 */
class PaymentProjectionServiceTest {

    private PaymentProjectionRepository payments;
    private RefundProjectionRepository refunds;
    private PaymentProjectionService service;

    @BeforeEach
    void setUp() {
        payments = mock(PaymentProjectionRepository.class);
        refunds = mock(RefundProjectionRepository.class);
        service = new PaymentProjectionService(new PaymentProjectionTxWriter(payments, refunds));
    }

    private static PaymentResponse payment(String id, String status, Instant createdAt) {
        return new PaymentResponse(id, status, 5000, "USD", "automatic", "cus_1", "stripe",
                "txn_1", null, null, createdAt, Map.of());
    }

    @Test
    void record_buildsRow_withTenantLivemode_andResponseCreatedAt() {
        Instant born = Instant.parse("2026-06-01T00:00:00Z");
        service.record(payment("pay_1", PaymentResponse.STATUS_PROCESSING, born), "tenant-A", false);

        ArgumentCaptor<PaymentProjectionRow> captor = ArgumentCaptor.forClass(PaymentProjectionRow.class);
        verify(payments).upsert(captor.capture());
        PaymentProjectionRow row = captor.getValue();
        assertThat(row.paymentId()).isEqualTo("pay_1");
        assertThat(row.tenantId()).isEqualTo("tenant-A");
        assertThat(row.livemode()).isFalse();
        assertThat(row.status()).isEqualTo(PaymentResponse.STATUS_PROCESSING);
        assertThat(row.amount()).isEqualTo(5000);
        assertThat(row.createdAt()).isEqualTo(born); // from the response, not now()
    }

    @Test
    void record_nullOrBlankId_isNoOp() {
        service.record(payment(null, PaymentResponse.STATUS_SUCCEEDED, Instant.now()), "tenant-A", true);
        // no upsert for a null id
        verify(payments, org.mockito.Mockito.never()).upsert(any());
    }

    @Test
    void record_nullOrBlankTenant_isNoOp_noNullTenantRowEverWritten() {
        // BLOCKER (F1): a live-create with no trusted tenant must NOT attempt a projection write —
        // tenant_id is NOT NULL, so a null-tenant upsert would throw the NOT NULL constraint at the
        // REQUIRES_NEW commit (outside the inner tx body) and leak into the live charge. The service
        // SKIPS it entirely (a null-tenant row is unqueryable anyway).
        service.record(payment("pay_live_nulltenant", PaymentResponse.STATUS_PROCESSING, Instant.now()), null, true);
        service.record(payment("pay_live_blanktenant", PaymentResponse.STATUS_PROCESSING, Instant.now()), "  ", true);
        verify(payments, org.mockito.Mockito.never()).upsert(any());
    }

    @Test
    void record_truncatesOverLengthErrorFields_toColumnCaps() {
        // BLOCKER (F1): a PSP error_code/error_message longer than the VARCHAR(128)/VARCHAR(512) caps would
        // overflow at the REQUIRES_NEW commit. The service truncates BEFORE the upsert so the write fits.
        String longCode = "x".repeat(200);     // > 128
        String longMessage = "y".repeat(1000);  // > 512
        PaymentResponse failed = new PaymentResponse("pay_fail", PaymentResponse.STATUS_FAILED, 5000, "USD",
                "automatic", "cus_1", "stripe", "txn_1", longCode, longMessage, Instant.now(), Map.of());

        service.record(failed, "tenant-A", false);

        ArgumentCaptor<PaymentProjectionRow> captor = ArgumentCaptor.forClass(PaymentProjectionRow.class);
        verify(payments).upsert(captor.capture());
        assertThat(captor.getValue().errorCode()).hasSize(128);
        assertThat(captor.getValue().errorMessage()).hasSize(512);
    }

    @Test
    void record_repositoryThrows_isSwallowed_neverPropagates() {
        doThrow(new RuntimeException("db down")).when(payments).upsert(any());

        assertThatCode(() -> service.record(
                payment("pay_x", PaymentResponse.STATUS_SUCCEEDED, Instant.now()), "tenant-A", true))
                .doesNotThrowAnyException();
    }

    @Test
    void recordRefund_repositoryThrows_isSwallowed() {
        doThrow(new RuntimeException("db down")).when(refunds).upsert(any());
        RefundResponse r = new RefundResponse("re_1", "pay_1", RefundResponse.STATUS_SUCCEEDED,
                2500, "USD", null, "stripe", "rc_1", null, null, Instant.now());

        assertThatCode(() -> service.recordRefund(r, "tenant-A", true)).doesNotThrowAnyException();
    }

    // ---- status precedence (the no-regression guard) ----

    @Test
    void paymentPrecedence_advancesForward_butNeverRegresses() {
        // processing -> succeeded advances
        assertThat(ProjectionStatusPrecedence.acceptPaymentStatus(
                PaymentResponse.STATUS_PROCESSING, PaymentResponse.STATUS_SUCCEEDED)).isTrue();
        // a late processing AFTER succeeded does NOT regress
        assertThat(ProjectionStatusPrecedence.acceptPaymentStatus(
                PaymentResponse.STATUS_SUCCEEDED, PaymentResponse.STATUS_PROCESSING)).isFalse();
        // the FIRST terminal wins — succeeded is not flipped to a different terminal (failed)
        assertThat(ProjectionStatusPrecedence.acceptPaymentStatus(
                PaymentResponse.STATUS_SUCCEEDED, PaymentResponse.STATUS_FAILED)).isFalse();
        // re-recording the SAME terminal is accepted (idempotent no-op update)
        assertThat(ProjectionStatusPrecedence.acceptPaymentStatus(
                PaymentResponse.STATUS_SUCCEEDED, PaymentResponse.STATUS_SUCCEEDED)).isTrue();
        // requires_capture advances to succeeded
        assertThat(ProjectionStatusPrecedence.acceptPaymentStatus(
                PaymentResponse.STATUS_REQUIRES_CAPTURE, PaymentResponse.STATUS_SUCCEEDED)).isTrue();
        // a fresh (null) row accepts anything
        assertThat(ProjectionStatusPrecedence.acceptPaymentStatus(null, PaymentResponse.STATUS_PROCESSING))
                .isTrue();
    }

    @Test
    void refundPrecedence_advancesForward_butNeverRegresses() {
        assertThat(ProjectionStatusPrecedence.acceptRefundStatus(
                RefundResponse.STATUS_PENDING, RefundResponse.STATUS_SUCCEEDED)).isTrue();
        assertThat(ProjectionStatusPrecedence.acceptRefundStatus(
                RefundResponse.STATUS_SUCCEEDED, RefundResponse.STATUS_PENDING)).isFalse();
        assertThat(ProjectionStatusPrecedence.acceptRefundStatus(
                RefundResponse.STATUS_SUCCEEDED, RefundResponse.STATUS_FAILED)).isFalse();
    }

    /**
     * End-to-end idempotency + no-regression through a fake in-memory repository wired with the SAME
     * precedence guard the JPA adapter uses: two records of the same payment_id collapse to ONE row,
     * created_at is preserved from the first write, and a late processing after succeeded does not regress.
     */
    @Test
    void record_isIdempotentByPk_preservesCreatedAt_andNeverRegresses() {
        Map<String, PaymentProjectionRow> store = new HashMap<>();
        PaymentProjectionRepository fake = new PaymentProjectionRepository() {
            @Override public void upsert(PaymentProjectionRow row) {
                store.merge(row.paymentId(), row, (existing, incoming) ->
                        ProjectionStatusPrecedence.acceptPaymentStatus(existing.status(), incoming.status())
                                ? new PaymentProjectionRow(existing.paymentId(), existing.tenantId(),
                                        existing.livemode(), incoming.status(), incoming.amount(),
                                        incoming.currency(), incoming.captureMethod(), incoming.customerId(),
                                        incoming.connectorName(), incoming.errorCode(), incoming.errorMessage(),
                                        existing.createdAt(), incoming.updatedAt()) // created_at preserved
                                : existing);
            }
            @Override public void updateStatusIfExists(String id, String t, String s, boolean l) { }
            @Override public java.util.List<PaymentProjectionRow> listByTenant(
                    String t, boolean l, String sf, String cf, int lim, int off) { return java.util.List.of(); }
        };
        PaymentProjectionService svc = new PaymentProjectionService(new PaymentProjectionTxWriter(fake, refunds));

        Instant born = Instant.parse("2026-06-01T00:00:00Z");
        svc.record(payment("pay_idem", PaymentResponse.STATUS_PROCESSING, born), "t", false);
        svc.record(payment("pay_idem", PaymentResponse.STATUS_SUCCEEDED, Instant.parse("2026-06-02T00:00:00Z")), "t", false);
        // a late/out-of-order processing must NOT regress the succeeded row
        svc.record(payment("pay_idem", PaymentResponse.STATUS_PROCESSING, Instant.parse("2026-06-03T00:00:00Z")), "t", false);

        assertThat(store).hasSize(1); // ONE row by PK
        PaymentProjectionRow row = store.get("pay_idem");
        assertThat(row.status()).isEqualTo(PaymentResponse.STATUS_SUCCEEDED); // forward, not regressed
        assertThat(row.createdAt()).isEqualTo(born); // birth time preserved from the first write
    }

    /**
     * F7 (no-Docker behavioral net): the async-live {@code recordStatusUpdate} actually drives
     * {@code updateStatusIfExists} through a fake that implements find-then-precedence-then-save (the same
     * shape as the JPA adapter). Asserts a STORED processing row advances to succeeded, a late processing
     * does NOT regress it, and an update on a MISSING id is a no-op (forward-fill — no row created). The
     * Testcontainers IT proves the same against real SQL; this locks it without Docker. Previously the
     * webhook test only verified the mock CALL, never the transition on a stored row.
     */
    @Test
    void recordStatusUpdate_advancesStoredRow_neverRegresses_andForwardFillIsNoOp() {
        Map<String, PaymentProjectionRow> store = new HashMap<>();
        PaymentProjectionRepository fake = new PaymentProjectionRepository() {
            @Override public void upsert(PaymentProjectionRow row) { store.put(row.paymentId(), row); }
            @Override public void updateStatusIfExists(String id, String t, String s, boolean l) {
                // find-then-precedence-then-save (mirrors JpaPaymentProjectionRepositoryAdapter): a missing
                // row is a NO-OP (never back-filled); an existing row advances only when precedence allows.
                PaymentProjectionRow e = store.get(id);
                if (e != null && ProjectionStatusPrecedence.acceptPaymentStatus(e.status(), s)) {
                    store.put(id, new PaymentProjectionRow(e.paymentId(), e.tenantId(), e.livemode(), s,
                            e.amount(), e.currency(), e.captureMethod(), e.customerId(), e.connectorName(),
                            e.errorCode(), e.errorMessage(), e.createdAt(), Instant.now()));
                }
            }
            @Override public java.util.List<PaymentProjectionRow> listByTenant(
                    String t, boolean l, String sf, String cf, int lim, int off) { return java.util.List.of(); }
        };
        PaymentProjectionService svc = new PaymentProjectionService(new PaymentProjectionTxWriter(fake, refunds));

        // a live create left a row at processing
        svc.record(payment("pay_async", PaymentResponse.STATUS_PROCESSING, Instant.parse("2026-06-01T00:00:00Z")),
                "t", true);

        // the payment_succeeded webhook advances it
        svc.recordStatusUpdate("pay_async", "t", PaymentResponse.STATUS_SUCCEEDED, true);
        assertThat(store.get("pay_async").status()).isEqualTo(PaymentResponse.STATUS_SUCCEEDED);

        // a late processing webhook does NOT regress it
        svc.recordStatusUpdate("pay_async", "t", PaymentResponse.STATUS_PROCESSING, true);
        assertThat(store.get("pay_async").status()).isEqualTo(PaymentResponse.STATUS_SUCCEEDED);

        // an update for an UNKNOWN id is a no-op (forward-fill: no row is created)
        svc.recordStatusUpdate("pay_never_seen", "t", PaymentResponse.STATUS_SUCCEEDED, true);
        assertThat(store).doesNotContainKey("pay_never_seen");
    }
}
