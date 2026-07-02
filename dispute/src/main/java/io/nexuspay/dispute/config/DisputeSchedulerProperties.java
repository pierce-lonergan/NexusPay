package io.nexuspay.dispute.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * GAP-033: configuration for the dispute evidence-deadline expiry scheduler, bound to
 * {@code nexuspay.dispute.deadline-expiry.*}.
 *
 * <p>Enabled by DEFAULT (a genuinely-overdue dispute must not silently stay OPENED/EVIDENCE_NEEDED
 * forever), but flag-disablable per deployment via {@code enabled=false} — the scheduler is annotated
 * {@code @ConditionalOnProperty(..., matchIfMissing=true)} on this flag (RefundReconciler precedent).</p>
 *
 * <ul>
 *   <li>{@code fixed-delay-ms} (default 300000 = 5 min): an evidence deadline is a coarse timer, so a
 *       5-minute sweep is ample and avoids hammering the DB;</li>
 *   <li>{@code batch-size} (default 200): the per-cycle upper bound on disputes discovered, so a backlog
 *       cannot OOM the sweep (successive cycles drain it — an expired dispute drops out of the finder).</li>
 * </ul>
 */
@Configuration
@ConfigurationProperties(prefix = "nexuspay.dispute.deadline-expiry")
public class DisputeSchedulerProperties {

    private boolean enabled = true;
    private long fixedDelayMs = 300_000L;
    private int batchSize = 200;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getFixedDelayMs() { return fixedDelayMs; }
    public void setFixedDelayMs(long fixedDelayMs) { this.fixedDelayMs = fixedDelayMs; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
}
