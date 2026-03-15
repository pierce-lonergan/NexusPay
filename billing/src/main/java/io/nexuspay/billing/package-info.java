/**
 * Subscription billing module — manages product catalog, pricing, subscription
 * lifecycle, invoice generation, dunning, and proration.
 *
 * <p>Allowed dependencies: {@code common}, {@code ledger}, {@code payment-orchestration}.</p>
 *
 * @since 0.2.5 (Sprint 2.5a), updated 0.2.5b (Sprint 2.5b)
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"common", "ledger", "payment-orchestration"}
)
package io.nexuspay.billing;
