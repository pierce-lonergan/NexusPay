/**
 * Observability module — custom Micrometer metrics, health indicators,
 * and SLO/SLI recording for the NexusPay platform.
 *
 * <p>Allowed dependencies: {@code common}.</p>
 *
 * @since 0.2.7 (Sprint 2.7)
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"common"}
)
package io.nexuspay.observability;
