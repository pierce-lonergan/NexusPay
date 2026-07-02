/**
 * Marketplace bounded context (Sprint 4.2).
 * Connected-account onboarding, split payments, platform fees, payouts.
 *
 * <p>Depends on {@code ledger} to book split-distribution journal entries
 * (GAP-063 — the Gradle module is {@code :ledger}; the Modulith module name is
 * derived from the {@code io.nexuspay.ledger} package). Declared exactly like
 * the SEC-24 dispute→ledger precedent ({@code dispute/package-info.java}).</p>
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"common", "ledger"}
)
package io.nexuspay.marketplace;
