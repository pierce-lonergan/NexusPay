/**
 * Reconciliation bounded context (Phase 2).
 * Settlement file ingestion, three-way matching, exception management, GL posting.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"common", "ledger"}
)
package io.nexuspay.reconciliation;
