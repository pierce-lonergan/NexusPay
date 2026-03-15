package io.nexuspay.fraud.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka topic provisioning for the fraud module.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
@Configuration
public class FraudKafkaConfig {

    @Value("${nexuspay.kafka.replication-factor:1}")
    private int replicationFactor;

    @Value("${nexuspay.kafka.partitions:6}")
    private int partitions;

    @Bean
    public NewTopic fraudAssessmentsTopic() {
        return TopicBuilder.name("nexuspay.fraud.assessments")
                .partitions(Math.max(partitions, 12))
                .replicas(replicationFactor)
                .config("retention.ms", String.valueOf(30L * 24 * 60 * 60 * 1000)) // 30 days
                .config("cleanup.policy", "delete")
                .build();
    }

    @Bean
    public NewTopic fraudEventsTopic() {
        return TopicBuilder.name("nexuspay.fraud.events")
                .partitions(Math.max(partitions, 12))
                .replicas(replicationFactor)
                .config("retention.ms", String.valueOf(90L * 24 * 60 * 60 * 1000)) // 90 days
                .config("cleanup.policy", "delete")
                .build();
    }

    @Bean
    public NewTopic fraudRulesChangelogTopic() {
        return TopicBuilder.name("nexuspay.fraud.rules.changelog")
                .partitions(partitions)
                .replicas(replicationFactor)
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000)) // 7 days
                .config("cleanup.policy", "delete")
                .build();
    }

    @Bean
    public NewTopic fraudDltTopic() {
        return TopicBuilder.name("nexuspay.fraud.DLT")
                .partitions(1)
                .replicas(replicationFactor)
                .config("retention.ms", String.valueOf(30L * 24 * 60 * 60 * 1000))
                .build();
    }
}
