package io.nexuspay.observability.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Custom Micrometer metrics for infrastructure health.
 *
 * <p>Tracks outbox lag, circuit breaker state, and Kafka consumer lag
 * as observable gauges that are scraped by Prometheus.</p>
 *
 * @since 0.2.7 (Sprint 2.7)
 */
@Component
public class InfrastructureMetrics {

    private final AtomicLong outboxLagSeconds = new AtomicLong(0);
    private final AtomicLong kafkaConsumerLag = new AtomicLong(0);

    public InfrastructureMetrics(MeterRegistry registry) {
        Gauge.builder("nexuspay.outbox.lag.seconds", outboxLagSeconds, AtomicLong::get)
                .description("Outbox relay lag in seconds (oldest unpublished event age)")
                .register(registry);

        Gauge.builder("nexuspay.kafka.consumer.lag", kafkaConsumerLag, AtomicLong::get)
                .description("Kafka consumer group lag (total across all partitions)")
                .register(registry);
    }

    public void setOutboxLagSeconds(long lag) {
        outboxLagSeconds.set(lag);
    }

    public void setKafkaConsumerLag(long lag) {
        kafkaConsumerLag.set(lag);
    }
}
