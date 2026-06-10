package io.nexuspay.iam.application;

import io.nexuspay.common.exception.AuthorizationException;
import io.nexuspay.iam.adapter.out.persistence.JpaPendingApprovalRepository;
import io.nexuspay.iam.adapter.out.persistence.PendingApprovalEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-009: maker-checker approve/reject must be execute-once and tenant-scoped, so
 * the same refund approval can't be executed twice (or by another tenant).
 */
class ApprovalServiceTest {

    private JpaPendingApprovalRepository repo;
    private AuditService audit;
    private ApprovalService svc;

    @BeforeEach
    void setUp() {
        repo = mock(JpaPendingApprovalRepository.class);
        audit = mock(AuditService.class);
        svc = new ApprovalService(repo, audit);
    }

    private PendingApprovalEntity pending(String tenant, String requester) {
        return new PendingApprovalEntity("appr_1", "refund", "Payment", "pay_1",
                Map.of("payment_id", "pay_1", "amount", 60000L), "PENDING",
                requester, null, tenant, Instant.now(), null);
    }

    @Test
    void approveWinsTheAtomicClaimExactlyOnce() {
        when(repo.findById("appr_1")).thenReturn(Optional.of(pending("t1", "maker")));
        when(repo.transitionFromPending(eq("appr_1"), eq("t1"), eq("APPROVED"), eq("checker"), any(Instant.class)))
                .thenReturn(1);

        svc.approve("appr_1", "checker", "t1");

        verify(repo).transitionFromPending(eq("appr_1"), eq("t1"), eq("APPROVED"), eq("checker"), any(Instant.class));
    }

    @Test
    void approveThrowsWhenClaimLost_concurrentDoubleApprove() {
        // Both requests read PENDING; only one's conditional UPDATE affects a row.
        when(repo.findById("appr_1")).thenReturn(Optional.of(pending("t1", "maker")));
        when(repo.transitionFromPending(any(), any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> svc.approve("appr_1", "checker", "t1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already processed");
    }

    @Test
    void cannotApproveOwnRequest() {
        when(repo.findById("appr_1")).thenReturn(Optional.of(pending("t1", "maker")));

        assertThatThrownBy(() -> svc.approve("appr_1", "maker", "t1"))
                .isInstanceOf(AuthorizationException.class);

        verify(repo, never()).transitionFromPending(any(), any(), any(), any(), any());
    }

    @Test
    void cannotApproveAnotherTenantsRequest() {
        when(repo.findById("appr_1")).thenReturn(Optional.of(pending("t1", "maker")));

        assertThatThrownBy(() -> svc.approve("appr_1", "checker", "t2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");

        verify(repo, never()).transitionFromPending(any(), any(), any(), any(), any());
    }

    @Test
    void cannotApproveAlreadyProcessed() {
        var approved = new PendingApprovalEntity("appr_1", "refund", "Payment", "pay_1",
                Map.of(), "APPROVED", "maker", "checker", "t1", Instant.now(), Instant.now());
        when(repo.findById("appr_1")).thenReturn(Optional.of(approved));

        assertThatThrownBy(() -> svc.approve("appr_1", "checker2", "t1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not pending");

        verify(repo, never()).transitionFromPending(any(), any(), any(), any(), any());
    }

    @Test
    void rejectIsAlsoAtomicAndTenantScoped() {
        when(repo.findById("appr_1")).thenReturn(Optional.of(pending("t1", "maker")));
        when(repo.transitionFromPending(eq("appr_1"), eq("t1"), eq("REJECTED"), eq("checker"), any(Instant.class)))
                .thenReturn(1);

        svc.reject("appr_1", "checker", "t1");

        verify(repo).transitionFromPending(eq("appr_1"), eq("t1"), eq("REJECTED"), eq("checker"), any(Instant.class));
    }
}
