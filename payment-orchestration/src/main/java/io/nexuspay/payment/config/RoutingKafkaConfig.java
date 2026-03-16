package io.nexuspay.payment.config;

import io.nexuspay.common.event.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka topic provisioning for routing events.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
@Configuration
public class RoutingKafkaConfig {

    @Bean
    public NewTopic routingDecisionsTopic() {
        return TopicBuilder.name(Topics.ROUTING_DECISIONS)
                .partitions(12)
                .replicas(1)
                .config("retention.ms", String.valueOf(30L * 24 * 60 * 60 * 1000)) // 30 days
                .build();
    }

    @Bean
    public NewTopic routingCascadesTopic() {
        return TopicBuilder.name(Topics.ROUTING_CASCADES)
                .partitions(12)
                .replicas(1)
                .config("retention.ms", String.valueOf(30L * 24 * 60 * 60 * 1000))
                .build();
    }

    @Bean
    public NewTopic routingFailuresTopic() {
        return TopicBuilder.name(Topics.ROUTING_FAILURES)
                .partitions(6)
                .replicas(1)
                .config("retention.ms", String.valueOf(90L * 24 * 60 * 60 * 1000)) // 90 days
                .build();
    }

    @Bean
    public NewTopic routingDltTopic() {
        return TopicBuilder.name(Topics.ROUTING_DLT)
                .partitions(1)
                .replicas(1)
                .config("retention.ms", String.valueOf(30L * 24 * 60 * 60 * 1000))
                .build();
    }
}
