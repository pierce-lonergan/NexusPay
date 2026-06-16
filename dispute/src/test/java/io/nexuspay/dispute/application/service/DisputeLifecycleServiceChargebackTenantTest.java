package io.nexuspay.dispute.application.service;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SEC-24: pins that chargeback ledger postings are scoped to the dispute's
 * server-authoritative tenant (from the HMAC-verified webhook, SEC-BATCH-2),
 * NOT the hardcoded {@code DEFAULT_TENANT} ("default").
 *
 * <p>Pure Mockito service test (no Postgres) — fast, in-module, and FAILS on the
 * pre-SEC-24 code where {@link LedgerPort} had no {@code tenantId} parameter and
 * {@code LedgerChargebackAdapter} posted every entry under {@code "default"}. The
 * 4-arg signature forces the threading; the {@code never(eq("default"))}
 * assertions pin the behavioural fix.</p>
 */
class DisputeLifecycleServiceChargebackTenantTest {

    private static final String TENANT = "tenant-X";

    private DisputeRepository disputeRepo;
    private EvidenceStoragePort evidence;
    private LedgerPort ledger;
    private DisputeLifecycleService svc;

    @BeforeEach
    void setUp() {
        disputeRepo = mock(DisputeRepository.class);
        evidence = mock(EvidenceStoragePort.class);
        ledger = mock(LedgerPort.class);
        svc = new DisputeLifecycleService(disputeRepo, evidence, ledger);
        when(disputeRepo.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(i -> i.getArgument(0));
    }

    /** A loaded, non-terminal dispute carrying the server-authoritative tenant. */
    private Dispute loadedDispute() {
        Dispute d = new Dispute();
        d.setId("dsp_1");
        d.setTenantId(TENANT);
        d.setPaymentId("pay_1");
        d.setExternalDisputeId("ext_1");
        d.setAmount(5000L);
        d.setCurrency("USD");
        d.setStatus(DisputeState.EVIDENCE_SUBMITTED);
        return d;
    }

    @Test
    void openDispute_postsChargebackReserveUnderDisputeTenant_notDefault() {
        when(disputeRepo.findByTenantIdAndExternalDisputeId(TENANT, "ext_1"))
                .thenReturn(Optional.empty());

        svc.openDispute(TENANT, "pay_1", "ext_1", "10.4", "fraud", 5000L, "USD", "visa", null);

        verify(ledger).createChargebackReserve(eq(TENANT), anyString(), eq(5000L), eq("USD"));
        verify(ledger, never()).createChargebackReserve(eq("default"), anyString(), anyLong(), anyString());
    }

    @Test
    void win_reversesChargebackReserveUnderDisputeTenant_notDefault() {
        when(disputeRepo.findById("dsp_1")).thenReturn(Optional.of(loadedDispute()));

        svc.win("dsp_1", "agent");

        verify(ledger).reverseChargebackReserve(eq(TENANT), anyString(), eq(5000L), eq("USD"));
        verify(ledger, never()).reverseChargebackReserve(eq("default"), anyString(), anyLong(), anyString());
    }

    @Test
    void lose_finalisesChargebackExpenseUnderDisputeTenant_notDefault() {
        when(disputeRepo.findById("dsp_1")).thenReturn(Optional.of(loadedDispute()));

        svc.lose("dsp_1", "agent");

        verify(ledger).finaliseChargebackExpense(eq(TENANT), anyString(), eq(5000L), eq("USD"));
        verify(ledger, never()).finaliseChargebackExpense(eq("default"), anyString(), anyLong(), anyString());
    }
}
