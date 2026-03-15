/**
 * Fraud prevention bounded context (Phase 3).
 * Rules engine, device fingerprinting, FRM provider integration,
 * risk scoring aggregation, and fraud assessment pipeline.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"common"}
)
package io.nexuspay.fraud;
