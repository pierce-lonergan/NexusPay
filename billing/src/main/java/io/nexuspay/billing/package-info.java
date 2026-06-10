/**
 * Subscription billing module — manages product catalog, pricing, subscription
 * lifecycle, invoice generation, dunning, and proration.
 *
 * <p>Allowed dependencies: {@code common}, {@code ledger}, {@code payment}
 * (the Modulith module name is derived from the package
 * {@code io.nexuspay.payment}, not the Gradle project name).</p>
 *
 * @since 0.2.5 (Sprint 2.5a), updated 0.2.5b (Sprint 2.5b)
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"common", "ledger", "payment"}
)
package io.nexuspay.billing;
