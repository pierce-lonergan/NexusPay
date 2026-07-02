/**
 * B2B payments bounded context (Sprint 4.3).
 * Purchase orders, B2B invoices, vendor payments, virtual cards, Level 2/3 data.
 *
 * <p>Depends on {@code ledger} to book invoice-payment and vendor-payment journal entries
 * (GAP-069) and on {@code iam} to REUSE the maker-checker {@code ApprovalService} machinery for
 * threshold-gated vendor-payment/PO approvals (GAP-068) — both edges declared exactly like the
 * SEC-24 dispute→ledger precedent (the Gradle modules are {@code :ledger}/{@code :iam}; the
 * Modulith module names derive from the {@code io.nexuspay.*} packages, and both targets are
 * declared OPEN).</p>
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"common", "ledger", "iam"}
)
package io.nexuspay.b2b;
