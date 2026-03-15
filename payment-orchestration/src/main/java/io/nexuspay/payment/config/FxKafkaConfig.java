package io.nexuspay.payment.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka topic provisioning for FX events.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
@Configuration
public class FxKafkaConfig {

    @Bean
    public NewTopic fxRatesTopic() {
        return TopicBuilder.name("nexuspay.fx.rates")
                .partitions(3)
                .replicas(1)
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000)) // 7 days
                .build();
    }

    @Bean
    public NewTopic fxConversionsTopic() {
        return TopicBuilder.name("nexuspay.fx.conversions")
                .partitions(12)
                .replicas(1)
                .config("retention.ms", String.valueOf(30L * 24 * 60 * 60 * 1000)) // 30 days
                .build();
    }

    @Bean
    public NewTopic fxLocksTopic() {
        return TopicBuilder.name("nexuspay.fx.locks")
                .partitions(6)
                .replicas(1)
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000)) // 7 days
                .build();
    }

    @Bean
    public NewTopic fxDltTopic() {
        return TopicBuilder.name("nexuspay.fx.DLT")
                .partitions(1)
                .replicas(1)
                .config("retention.ms", String.valueOf(30L * 24 * 60 * 60 * 1000)) // 30 days
                .build();
    }
}
