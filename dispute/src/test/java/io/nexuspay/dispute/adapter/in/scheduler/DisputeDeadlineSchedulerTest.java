package io.nexuspay.dispute.adapter.in.scheduler;

import io.nexuspay.common.rls.TenantWorkRunner;
import io.nexuspay.dispute.application.port.out.DisputeRepository;
import io.nexuspay.dispute.application.service.DisputeLifecycleService;
import io.nexuspay.dispute.config.DisputeSchedulerProperties;
import io.nexuspay.dispute.domain.Dispute;
import io.nexuspay.dispute.domain.DisputeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GAP-033: the deadline scheduler is a thin cross-tenant discovery + per-tenant driver over the EXISTING
 * atomic+idempotent {@code DisputeLifecycleService.expire}. This unit test pins the driver behaviour:
 * <ol>
 *   <li>each discovered dispute is expired once, bound to ITS OWN tenant via
 *       {@code TenantWorkRunner.runInTenant};</li>
 *   <li>one dispute's expire throwing does NOT stop the others (per-item failure isolation);</li>
 *   <li>the finder is queried with {@code Instant.now()} and the configured batch size.</li>
 * </ol>
 *
 * <p>The status-filter correctness (only OPENED/EVIDENCE_NEEDED past-due are selected; EVIDENCE_SUBMITTED
 * and terminals excluded) lives in the finder predicate and is covered by
 * {@code DisputeRepositoryFindExpirableTest}; the idempotent-no-double-post guarantee lives in
 * {@code expire()} and is covered by {@code DisputeExpireIdempotencyTest}.</p>
 */
class DisputeDeadlineSchedulerTest {

    private DisputeRepository repo;
    private DisputeLifecycleService lifecycle;
    private TenantWorkRunner tenantWork;
    private DisputeDeadlineScheduler scheduler;

    @BeforeEach
    void setUp() {
        repo = mock(DisputeRepository.class);
        lifecycle = mock(DisputeLifecycleService.class);
        tenantWork = mock(TenantWorkRunner.class);

        // Make runInTenant just execute the work synchronously so we can observe the delegated expire calls.
        doAnswer(inv -> {
            Runnable work = inv.getArgument(1);
            work.run();
            return null;
        }).when(tenantWork).runInTenant(any(), any(Runnable.class));

        DisputeSchedulerProperties props = new DisputeSchedulerProperties();
        props.setBatchSize(200);
        scheduler = new DisputeDeadlineScheduler(repo, lifecycle, tenantWork, props);
    }

    private Dispute dispute(String id, String tenant, DisputeState state) {
        Dispute d = new Dispute();
        d.setId(id);
        d.setTenantId(tenant);
        d.setStatus(state);
        d.setAmount(5000L);
        d.setCurrency("USD");
        return d;
    }

    @Test
    void expiresEachDueDispute_onceUnderItsOwnTenant() {
        Dispute d1 = dispute("dp_1", "tenant-A", DisputeState.OPENED);
        Dispute d2 = dispute("dp_2", "tenant-A", DisputeState.EVIDENCE_NEEDED);
        Dispute d3 = dispute("dp_3", "tenant-B", DisputeState.OPENED);
        when(repo.findExpirable(any(Instant.class), anyInt())).thenReturn(List.of(d1, d2, d3));

        scheduler.expireOverdueDisputes();

        // Each expire is bound to the DISPUTE's own tenant (tenant-A x2, tenant-B x1).
        verify(tenantWork, times(2)).runInTenant(eq("tenant-A"), any(Runnable.class));
        verify(tenantWork, times(1)).runInTenant(eq("tenant-B"), any(Runnable.class));
        verify(lifecycle).expire("dp_1");
        verify(lifecycle).expire("dp_2");
        verify(lifecycle).expire("dp_3");
    }

    @Test
    void oneDisputeFailure_doesNotStopTheOthers() {
        Dispute d1 = dispute("dp_1", "tenant-A", DisputeState.OPENED);
        Dispute d2 = dispute("dp_2", "tenant-B", DisputeState.EVIDENCE_NEEDED);
        Dispute d3 = dispute("dp_3", "tenant-C", DisputeState.OPENED);
        when(repo.findExpirable(any(Instant.class), anyInt())).thenReturn(List.of(d1, d2, d3));

        // The middle dispute's expire throws — must not abort the sweep.
        doThrow(new RuntimeException("ledger down")).when(lifecycle).expire("dp_2");

        scheduler.expireOverdueDisputes();

        // All three were attempted; the failure of dp_2 did not prevent dp_1 and dp_3.
        verify(lifecycle).expire("dp_1");
        verify(lifecycle).expire("dp_2");
        verify(lifecycle).expire("dp_3");
    }

    @Test
    void emptyBatch_noExpireCalls() {
        when(repo.findExpirable(any(Instant.class), anyInt())).thenReturn(List.of());

        scheduler.expireOverdueDisputes();

        verify(lifecycle, times(0)).expire(any());
        verify(tenantWork, times(0)).runInTenant(any(), any(Runnable.class));
    }
}
