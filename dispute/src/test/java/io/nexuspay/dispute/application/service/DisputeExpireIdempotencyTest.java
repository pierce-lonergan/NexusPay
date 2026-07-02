package io.nexuspay.dispute.application.service;

import io.nexuspay.dispute.application.port.out.DisputeOutboxPort;
import io.nexuspay.dispute.application.port.out.DisputeRepository;
import io.nexuspay.dispute.application.port.out.EvidenceStoragePort;
import io.nexuspay.dispute.application.port.out.LedgerPort;
import io.nexuspay.dispute.domain.Dispute;
import io.nexuspay.dispute.domain.DisputeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GAP-033 money-safety: {@code expire()} moves money (it finalises the chargeback as an expense — the same
 * DR chargeback_expense / CR chargeback_reserve posting as {@code lose()}), so the deadline scheduler
 * inherits WAVE-1's CARDINAL RULE. This test reinforces that the rule is INTRINSIC to {@code expire()} —
 * the scheduler re-run / racing-replica case is safe because:
 * <ul>
 *   <li>the ledger post is ATOMIC with the transition (both inside {@code expire()}'s single
 *       {@code @Transactional}), and</li>
 *   <li>a SECOND expire of an already-EXPIRED dispute is a no-op — {@code dispute.expire()} short-circuits
 *       on a terminal state (producing no new event) and the service posts to the ledger ONLY when the
 *       transition actually happened, so the chargeback expense is finalised EXACTLY ONCE.</li>
 * </ul>
 * This is precisely what makes the scheduler safe to run on multiple replicas / across cycles without a
 * cross-instance lock.
 */
class DisputeExpireIdempotencyTest {

    private static final String TENANT = "tenant-X";

    private DisputeRepository disputeRepo;
    private EvidenceStoragePort evidence;
    private LedgerPort ledger;
    private DisputeOutboxPort outbox;
    private DisputeLifecycleService svc;

    @BeforeEach
    void setUp() {
        disputeRepo = mock(DisputeRepository.class);
        evidence = mock(EvidenceStoragePort.class);
        ledger = mock(LedgerPort.class);
        outbox = mock(DisputeOutboxPort.class);
        svc = new DisputeLifecycleService(disputeRepo, evidence, ledger, outbox);
        when(disputeRepo.save(org.mockito.ArgumentMatchers.any())).thenAnswer(i -> i.getArgument(0));
    }

    private Dispute overdue(DisputeState state) {
        Dispute d = new Dispute();
        d.setId("dp_1");
        d.setTenantId(TENANT);
        d.setPaymentId("pay_1");
        d.setExternalDisputeId("ext_1");
        d.setAmount(5000L);
        d.setCurrency("USD");
        d.setStatus(state);
        return d;
    }

    @Test
    void firstExpire_postsChargebackExpenseOnce_underDisputeTenant() {
        when(disputeRepo.findById("dp_1")).thenReturn(Optional.of(overdue(DisputeState.EVIDENCE_NEEDED)));

        svc.expire("dp_1");

        verify(ledger, times(1))
                .finaliseChargebackExpense(eq(TENANT), eq("dp_1"), eq(5000L), eq("USD"));
    }

    @Test
    void secondExpire_onAlreadyExpiredDispute_isNoOp_noSecondLedgerPost() {
        // Simulate the racing-replica / re-run: the dispute is ALREADY EXPIRED (terminal) when re-driven.
        when(disputeRepo.findById("dp_1")).thenReturn(Optional.of(overdue(DisputeState.EXPIRED)));

        svc.expire("dp_1");

        // No transition happened -> the ledger is NOT posted again (no double-post / no reserve double-drain).
        verify(ledger, never()).finaliseChargebackExpense(anyString(), anyString(), anyLong(), anyString());
    }
}
