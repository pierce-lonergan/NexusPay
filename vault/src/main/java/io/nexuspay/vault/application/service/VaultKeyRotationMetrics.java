package io.nexuspay.vault.application.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * GAP-059: Micrometer counters for the encryption key-rotation job. Mirrors the
 * {@code LedgerMetrics} template (a thin wrapper over {@link MeterRegistry}). No card data
 * (PAN, ciphertext, key material) is ever a tag or value — only outcome counts are recorded.
 *
 * <ul>
 *   <li>{@code nexuspay.vault.key_rotation.rotated} — cards re-encrypted onto the active key;</li>
 *   <li>{@code nexuspay.vault.key_rotation.skipped} — cards already off the retired key (idempotent);</li>
 *   <li>{@code nexuspay.vault.key_rotation.failed}  — cards whose re-encryption threw (left untouched,
 *       re-driven next cycle).</li>
 * </ul>
 */
@Component
public class VaultKeyRotationMetrics {

    private final MeterRegistry registry;

    public VaultKeyRotationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordRotated() {
        counter("rotated", "Cards re-encrypted onto the active key").increment();
    }

    public void recordSkipped() {
        counter("skipped", "Cards already off the retired key (idempotent skip)").increment();
    }

    public void recordFailed() {
        counter("failed", "Cards whose key rotation failed (left on the retired key, re-driven)").increment();
    }

    private Counter counter(String outcome, String description) {
        return Counter.builder("nexuspay.vault.key_rotation." + outcome)
                .description(description)
                .register(registry);
    }
}
