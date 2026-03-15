/**
 * Subscription billing module — manages product catalog, pricing, subscription
 * lifecycle, invoice generation, dunning, and proration.
 *
 * <p>Allowed dependencies: {@code common}, {@code ledger}.</p>
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"common", "ledger"}
)
package io.nexuspay.billing;
