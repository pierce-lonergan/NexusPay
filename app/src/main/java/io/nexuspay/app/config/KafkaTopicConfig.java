package io.nexuspay.app.config;

import io.nexuspay.common.event.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka topic provisioning via NewTopic beans.
 * Topics are auto-created on application startup.
 *
 * Replication factor: 1 for dev, 3 for production (set via config).
 * Partition count: 6 for domain topics (allows scaling consumers).
 */
@Configuration
public class KafkaTopicConfig {

    @Value("${nexuspay.kafka.replication-factor:1}")
    private int replicationFactor;

    @Value("${nexuspay.kafka.partitions:6}")
    private int partitions;

    @Bean
    public NewTopic paymentsTopic() {
        return TopicBuilder.name(Topics.PAYMENTS)
                .partitions(partitions)
                .replicas(replicationFactor)
                .config("retention.ms", "604800000") // 7 days
                .config("cleanup.policy", "delete")
                .build();
    }

    @Bean
    public NewTopic ledgerTopic() {
        return TopicBuilder.name(Topics.LEDGER)
                .partitions(partitions)
                .replicas(replicationFactor)
                .config("retention.ms", "604800000") // 7 days
                .config("cleanup.policy", "delete")
                .build();
    }

    @Bean
    public NewTopic paymentsDltTopic() {
        return TopicBuilder.name(Topics.PAYMENTS_DLT)
                .partitions(1) // DLT doesn't need high parallelism
                .replicas(replicationFactor)
                .config("retention.ms", "2592000000") // 30 days
                .build();
    }

    @Bean
    public NewTopic ledgerDltTopic() {
        return TopicBuilder.name(Topics.LEDGER_DLT)
                .partitions(1)
                .replicas(replicationFactor)
                .config("retention.ms", "2592000000") // 30 days
                .build();
    }
}
