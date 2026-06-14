package io.nexuspay.observability.metrics;

import io.nexuspay.common.rls.SystemTransactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically measures the outbox relay lag — the age (in seconds)
 * of the oldest unpublished event in the {@code event_outbox} table.
 *
 * <p>This metric powers the {@code OutboxLagHigh} alert rule. A lag
 * greater than 30 seconds indicates either Kafka connectivity issues
 * or an outbox relay leader election problem.</p>
 *
 * @since 0.2.7 (Sprint 2.7)
 */
@Component
public class OutboxLagMonitor {

    private static final Logger log = LoggerFactory.getLogger(OutboxLagMonitor.class);

    private final JdbcTemplate jdbcTemplate;
    private final InfrastructureMetrics metrics;

    public OutboxLagMonitor(JdbcTemplate jdbcTemplate, InfrastructureMetrics metrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.metrics = metrics;
    }

    /**
     * Runs every 10 seconds — queries the oldest unpublished outbox event age.
     */
    @SystemTransactional
    @Scheduled(fixedDelay = 10_000)
    public void measureOutboxLag() {
        try {
            Long lagSeconds = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(EXTRACT(EPOCH FROM (NOW() - MIN(created_at))), 0) " +
                    "FROM event_outbox WHERE published_at IS NULL",
                    Long.class
            );
            metrics.setOutboxLagSeconds(lagSeconds != null ? lagSeconds : 0);
        } catch (Exception e) {
            log.debug("Could not measure outbox lag: {}", e.getMessage());
        }
    }
}
