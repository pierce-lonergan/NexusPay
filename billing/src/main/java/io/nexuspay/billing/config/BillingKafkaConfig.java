package io.nexuspay.billing.config;

import io.nexuspay.common.event.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Kafka topic provisioning for the billing module.
 *
 * <p>Creates the {@code nexuspay.billing} topic and its dead letter topic
 * on startup. Partition count and replication factor are configurable
 * via application properties.</p>
 *
 * @since 0.2.5b (Sprint 2.5b)
 */
@Configuration
public class BillingKafkaConfig {

    @Value("${nexuspay.kafka.partitions:6}")
    private int partitions;

    @Value("${nexuspay.kafka.replication-factor:1}")
    private int replicationFactor;

    @Bean
    public NewTopic billingTopic() {
        return new NewTopic(Topics.BILLING, partitions, (short) replicationFactor);
    }

    @Bean
    public NewTopic billingDltTopic() {
        return new NewTopic(Topics.BILLING_DLT, partitions, (short) replicationFactor);
    }
}
