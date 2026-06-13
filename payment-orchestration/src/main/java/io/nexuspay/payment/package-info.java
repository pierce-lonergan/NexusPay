/**
 * Payment orchestration bounded context — HyperSwitch client, webhook receiver,
 * transactional outbox relay, routing engine, and FX/cross-border services.
 *
 * <p>Declared {@code OPEN} (transitional): consumers (gateway, billing,
 * workflow) use the hexagonal API in {@code application.port} and
 * {@code domain}, which Modulith's default base-package-only exposure would
 * flag as internal access. The long-term fix is {@code @NamedInterface}
 * declarations on the port/domain packages.</p>
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        // "fraud" added (B-024): the GatedPaymentGateway pre-auth decorator calls the
        // fraud inbound port (AssessFraudRiskUseCase) + its DTOs. Acyclic: fraud→common only.
        allowedDependencies = {"common", "fraud"}
)
package io.nexuspay.payment;
