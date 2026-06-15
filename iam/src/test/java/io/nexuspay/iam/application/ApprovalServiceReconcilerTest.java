package io.nexuspay.iam.application;

import io.nexuspay.iam.adapter.out.persistence.JpaPendingApprovalRepository;
import io.nexuspay.iam.adapter.out.persistence.PendingApprovalEntity;
import io.nexuspay.iam.domain.PendingApproval;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-022: the iam-side reconciler seam. These tests pin the EXACT repository contract the reconciler
 * relies on — the conditional executed-marking returns 1 only when it actually flips the row (real
 * UPDATE semantics, not a stub that always "succeeds"), the failure record is tenant-bound, and the
 * discovery/exhausted finders pass through the right filters. Mirrors ApprovalServiceTest's convention
 * of modeling the conditional UPDATE's 0/1 result faithfully (L-039).
 */
class ApprovalServiceReconcilerTest {

    private JpaPendingApprovalRepository repo;
    private ApprovalService svc;

    @BeforeEach
    void setUp() {
        repo = mock(JpaPendingApprovalRepository.class);
        svc = new ApprovalService(repo, mock(AuditService.class));
    }

    private PendingApprovalEntity refund(String id, String tenant, Instant executedAt, int attempts) {
        var e = new PendingApprovalEntity(id, "refund", "Payment", "pay_1",
                Map.of("payment_id", "pay_1", "amount", 60000L), "APPROVED", "maker", "checker",
                tenant, Instant.now(), Instant.now());
        e.setExecutedAt(executedAt);
        e.setReconcileAttempts(attempts);
        return e;
    }

    @Test
    void findStuckApprovedRefunds_passesBatchAsPageableAndMapsToDomain() {
        when(repo.findApprovedUnexecutedRefunds(any(Instant.class), eq(5), any(Pageable.class)))
                .thenReturn(List.of(refund("appr_1", "t1", null, 0)));

        List<PendingApproval> out = svc.findStuckApprovedRefunds(Instant.now(), 5, 100);

        assertThat(out).extracting(PendingApproval::getId).containsExactly("appr_1");
        assertThat(out.get(0).isExecuted()).isFalse();
        verify(repo).findApprovedUnexecutedRefunds(any(Instant.class), eq(5), eq(PageRequest.of(0, 100)));
    }

    @Test
    void markRefundExecuted_returnsTrueOnlyWhenConditionalUpdateFlippedTheRow() {
        // Real conditional UPDATE: 1 = this caller flipped executed_at NULL -> now.
        when(repo.markRefundExecuted(eq("appr_1"), eq("t1"), any(Instant.class))).thenReturn(1);
        assertThat(svc.markRefundExecuted("appr_1", "t1")).isTrue();
    }

    @Test
    void markRefundExecuted_returnsFalseWhenAnotherWriterAlreadyMarked() {
        // 0 = executed_at was already non-null (a concurrent success won) → not flipped here.
        when(repo.markRefundExecuted(eq("appr_1"), eq("t1"), any(Instant.class))).thenReturn(0);
        assertThat(svc.markRefundExecuted("appr_1", "t1")).isFalse();
    }

    @Test
    void recordReconcileFailure_isBoundToTheRowsTenantAndPersistsBackoffPlusError() {
        Instant next = Instant.now().plusSeconds(120);
        svc.recordReconcileFailure("appr_1", "t1", next, "gateway_error: boom");
        verify(repo).recordReconcileFailure(eq("appr_1"), eq("t1"), eq(next), eq("gateway_error: boom"));
    }

    @Test
    void recordReconcileFailure_truncatesAnOverlongError() {
        String huge = "x".repeat(5000);
        svc.recordReconcileFailure("appr_1", "t1", Instant.now(), huge);
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repo).recordReconcileFailure(eq("appr_1"), eq("t1"), any(Instant.class), captor.capture());
        assertThat(captor.getValue()).hasSize(1000);
    }

    @Test
    void recordPendingRecheck_isBoundToTheRowsTenantAndSetsGateWithoutTouchingAttempts() {
        // B-022 FIX 2: benign PSP `pending` re-check delegates to the conditional UPDATE that sets the
        // next gate + note WITHOUT incrementing reconcile_attempts (a distinct repo method from the
        // failure path). Tenant-bound, exactly like recordReconcileFailure.
        Instant next = Instant.now().plusSeconds(120);
        svc.recordPendingRecheck("appr_1", "t1", next, "gateway refund pending");
        verify(repo).recordPendingRecheck(eq("appr_1"), eq("t1"), eq(next), eq("gateway refund pending"));
    }

    @Test
    void recordPendingRecheck_truncatesAnOverlongNote() {
        String huge = "x".repeat(5000);
        svc.recordPendingRecheck("appr_1", "t1", Instant.now(), huge);
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repo).recordPendingRecheck(eq("appr_1"), eq("t1"), any(Instant.class), captor.capture());
        assertThat(captor.getValue()).hasSize(1000);
    }

    @Test
    void reloadUnexecutedForUpdate_returnsEmptyWhenAlreadyExecuted() {
        when(repo.findByIdForUpdate("appr_1"))
                .thenReturn(Optional.of(refund("appr_1", "t1", Instant.now(), 0)));
        assertThat(svc.reloadUnexecutedForUpdate("appr_1")).isEmpty();
    }

    @Test
    void reloadUnexecutedForUpdate_returnsRowWhenStillUnexecuted() {
        when(repo.findByIdForUpdate("appr_1"))
                .thenReturn(Optional.of(refund("appr_1", "t1", null, 2)));
        var out = svc.reloadUnexecutedForUpdate("appr_1");
        assertThat(out).isPresent();
        assertThat(out.get().getReconcileAttempts()).isEqualTo(2);
    }

    @Test
    void findExhaustedRefunds_delegatesWithMaxAttempts() {
        when(repo.findExhaustedRefunds(5)).thenReturn(List.of(refund("appr_max", "t1", null, 5)));
        assertThat(svc.findExhaustedRefunds(5)).extracting(PendingApproval::getId).containsExactly("appr_max");
        verify(repo).findExhaustedRefunds(eq(5));
    }
}
