package io.nexuspay.app.config;

import io.nexuspay.ledger.application.CreateJournalEntryUseCase;
import io.nexuspay.ledger.application.port.JournalEntryRepository;
import io.nexuspay.ledger.application.port.LedgerAccountRepository;
import io.nexuspay.ledger.domain.JournalEntry;

/**
 * WAVE1-money-ledger: test-only, armable fault-injection seam over {@link CreateJournalEntryUseCase},
 * registered {@code @Primary} in the SHARED integration-test context (see
 * {@link TestSecurityConfig#faultInjectableCreateJournalEntryUseCase}) — the
 * {@link FaultInjectableThreeWayMatchingService} pattern applied to the ledger write path.
 *
 * <p>Powers {@code LedgerPostingAtomicityIT}, the anti-best-effort proof for GAP-063/069: with a
 * fault armed, every ledger posting attempted on the arming thread throws, and the test asserts
 * the surrounding money-state transition (split creation / invoice markPaid / vendor approval)
 * ROLLED BACK with it. Lives in the shared config rather than as a {@code @MockBean} so it does
 * not fork a second full Testcontainers context (the OOM rationale documented on the matching
 * seam).</p>
 *
 * <p>Default behavior is byte-identical to production: unless {@link #armFault} was called on the
 * current thread, {@link #execute} delegates straight to {@code super.execute}. The armed fault is
 * a {@link ThreadLocal}; tests MUST clear it in a {@code finally}. All posting call sites this
 * wave introduces are synchronous on the caller's thread (that is the point), so the orchestrating
 * test thread is always the throwing thread. Spring still applies the parent method's
 * {@code @Transactional} attributes to the override (annotation lookup traverses the overridden
 * method), and REQUIRED propagation means the throw happens INSIDE the caller's transaction.</p>
 */
public class FaultInjectableCreateJournalEntryUseCase extends CreateJournalEntryUseCase {

    private static final ThreadLocal<RuntimeException> ARMED_FAULT = new ThreadLocal<>();

    public FaultInjectableCreateJournalEntryUseCase(JournalEntryRepository journalEntryRepository,
                                                    LedgerAccountRepository ledgerAccountRepository) {
        super(journalEntryRepository, ledgerAccountRepository);
    }

    /** Arm {@code execute()} to throw {@code fault} on the current thread (every invocation until cleared). */
    public static void armFault(RuntimeException fault) {
        ARMED_FAULT.set(fault);
    }

    /** Disarm — MUST be called in a {@code finally} so the fault never bleeds to a later test. */
    public static void clearFault() {
        ARMED_FAULT.remove();
    }

    @Override
    public JournalEntry execute(CreateJournalEntryCommand command) {
        RuntimeException fault = ARMED_FAULT.get();
        if (fault != null) {
            throw fault;
        }
        return super.execute(command);
    }
}
