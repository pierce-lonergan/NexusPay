/**
 * Fraud prevention bounded context (Phase 3).
 * Rules engine, device fingerprinting, FRM provider integration,
 * risk scoring aggregation, and fraud assessment pipeline.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
@org.springframework.modulith.ApplicationModule(
        // OPEN so the gateway pre-authorization gate (B-003) can call the inbound
        // port AssessFraudRiskUseCase + its DTOs, consistent with the other
        // cross-cut modules (common/payment/ledger/iam are already OPEN).
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        allowedDependencies = {"common"}
)
package io.nexuspay.fraud;
