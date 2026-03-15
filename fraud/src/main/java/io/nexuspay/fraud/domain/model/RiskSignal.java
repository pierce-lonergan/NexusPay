package io.nexuspay.fraud.domain.model;

/**
 * An individual risk signal produced during fraud evaluation.
 *
 * @param source      signal origin (e.g., "velocity_rule", "sift", "device_fingerprint")
 * @param signalName  human-readable name (e.g., "high_velocity_card", "new_device")
 * @param score       score contribution (0-100)
 * @param details     additional context
 * @since 0.3.0 (Sprint 3.1)
 */
public record RiskSignal(
        String source,
        String signalName,
        int score,
        String details
) {}
