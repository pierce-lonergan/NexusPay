package io.nexuspay.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Custom Micrometer metrics for ledger operations.
 *
 * @since 0.2.7 (Sprint 2.7)
 */
@Component
public class LedgerMetrics {

    private final MeterRegistry registry;

    public LedgerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Records a journal entry creation.
     */
    public void recordJournalEntryCreated(String currency) {
        Counter.builder("nexuspay.ledger.entries.created")
                .description("Total journal entries created")
                .tag("currency", currency)
                .register(registry)
                .increment();
    }

    /**
     * Records a reconciliation match result.
     */
    public void recordReconciliationResult(String status) {
        Counter.builder("nexuspay.reconciliation.results")
                .description("Reconciliation match results")
                .tag("status", status)
                .register(registry)
                .increment();
    }
}
