package io.nexuspay.analytics.domain.event;

import java.time.Instant;

/**
 * Event published when a PSP's health score drops below acceptable levels.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
public record PspHealthDegraded(
        String tenantId,
        String pspConnector,
        int healthScore,
        int previousScore,
        String reason,
        Instant detectedAt
) {}
