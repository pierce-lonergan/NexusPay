package io.nexuspay.observability.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health indicator that monitors Kafka consumer group lag.
 *
 * <p>Reports WARNING if lag exceeds 100 messages across any consumer
 * group, and DOWN if lag exceeds 10,000 messages (indicates consumer
 * is not keeping up or is disconnected).</p>
 *
 * @since 0.2.7 (Sprint 2.7)
 */
@Component("kafkaConsumer")
public class KafkaConsumerHealthIndicator implements HealthIndicator {

    private static final long WARNING_THRESHOLD = 100;
    private static final long CRITICAL_THRESHOLD = 10_000;

    private volatile long currentLag = 0;

    public void updateLag(long lag) {
        this.currentLag = lag;
    }

    @Override
    public Health health() {
        if (currentLag >= CRITICAL_THRESHOLD) {
            return Health.down()
                    .withDetail("consumerLag", currentLag)
                    .withDetail("threshold", CRITICAL_THRESHOLD)
                    .withDetail("message", "Consumer lag critically high — consumer may be disconnected")
                    .build();
        } else if (currentLag >= WARNING_THRESHOLD) {
            return Health.status("WARNING")
                    .withDetail("consumerLag", currentLag)
                    .withDetail("threshold", WARNING_THRESHOLD)
                    .withDetail("message", "Consumer lag above warning threshold")
                    .build();
        }
        return Health.up()
                .withDetail("consumerLag", currentLag)
                .build();
    }
}
