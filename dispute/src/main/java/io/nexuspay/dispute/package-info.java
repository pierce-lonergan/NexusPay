/**
 * Dispute bounded context (Phase 2).
 * Chargeback lifecycle, evidence assembly, deadline enforcement, representment tracking.
 *
 * <p>Depends on {@code ledger} to book chargeback reserve/expense journal
 * entries (the Gradle module is {@code :ledger}; the Modulith module name is
 * derived from the {@code io.nexuspay.ledger} package).</p>
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"common", "ledger"}
)
package io.nexuspay.dispute;
