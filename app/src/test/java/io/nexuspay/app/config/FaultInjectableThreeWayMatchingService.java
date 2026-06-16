package io.nexuspay.app.config;

import io.nexuspay.reconciliation.application.port.out.LedgerQueryPort;
import io.nexuspay.reconciliation.application.port.out.PaymentQueryPort;
import io.nexuspay.reconciliation.application.service.ThreeWayMatchingService;
import io.nexuspay.reconciliation.domain.MatchResult;
import io.nexuspay.reconciliation.domain.SettlementRecord;

import java.util.List;

/**
 * Test-only, armable fault-injection seam over {@link ThreeWayMatchingService}, registered {@code @Primary}
 * in the SHARED integration-test context (see {@link TestSecurityConfig#faultInjectableMatchingService}).
 *
 * <p><strong>Why this exists instead of {@code @MockBean}:</strong> a {@code @MockBean} bean definition
 * is part of Spring's context-cache key, so it forks a SECOND full Testcontainers application context that
 * the cache holds alongside the shared one. With 16 Modulith modules + Postgres/Kafka/Redis clients per
 * context (~300&nbsp;MB each), that extra cached context exhausted the {@code :app:test} JVM heap and
 * OOM-cascaded the whole suite. Living in the shared {@code TestSecurityConfig} keeps the context-cache key
 * identical across every IT — one context, no fork. Mirrors the INT-3 {@code MockPaymentGatewayPort} +
 * {@code ThreadLocal} armable-double precedent.</p>
 *
 * <p><strong>Default behavior is identical to production:</strong> unless a test arms a fault on the
 * current thread via {@link #armFault}, {@link #reconcile} delegates straight to {@code super.reconcile}.
 * The fault is a {@link ThreadLocal}, so it cannot leak across the parallel-safe test threads; tests MUST
 * clear it in a {@code finally} (see {@link #clearFault}). The reconcile path is fully synchronous
 * (no {@code @Async}), so the orchestrator invokes this on the arming thread.</p>
 */
public class FaultInjectableThreeWayMatchingService extends ThreeWayMatchingService {

    private static final ThreadLocal<RuntimeException> ARMED_FAULT = new ThreadLocal<>();

    public FaultInjectableThreeWayMatchingService(PaymentQueryPort paymentQueryPort,
                                                  LedgerQueryPort ledgerQueryPort) {
        super(paymentQueryPort, ledgerQueryPort);
    }

    /** Arm {@code reconcile()} to throw {@code fault} on the current thread (next invocation). */
    public static void armFault(RuntimeException fault) {
        ARMED_FAULT.set(fault);
    }

    /** Disarm — MUST be called in a {@code finally} so the fault never bleeds to a later test. */
    public static void clearFault() {
        ARMED_FAULT.remove();
    }

    @Override
    public List<MatchResult> reconcile(List<SettlementRecord> settlements) {
        RuntimeException fault = ARMED_FAULT.get();
        if (fault != null) {
            throw fault;
        }
        return super.reconcile(settlements);
    }
}
