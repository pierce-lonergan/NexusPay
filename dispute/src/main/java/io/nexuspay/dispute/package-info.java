/**
 * Dispute bounded context (Phase 2).
 * Chargeback lifecycle, evidence assembly, deadline enforcement, representment tracking.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"common", "payment"}
)
package io.nexuspay.dispute;
