package io.nexuspay.app.config;

import io.nexuspay.common.event.avro.DualFormatDeserializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;

import java.util.HashMap;
import java.util.Map;

/**
 * Batch Kafka consumer configuration for high-throughput event consumption.
 *
 * <p>Uses the same {@link DualFormatDeserializer} as the standard consumer factory
 * but with batch mode enabled. Batch size: 50 records, poll timeout: 1000ms.
 *
 * <p>Usage: annotate a listener with
 * {@code @KafkaListener(containerFactory = "batchKafkaListenerContainerFactory")}
 *
 * @since 0.3.0 (Sprint 3.4)
 */
@Configuration
public class BatchKafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(BatchKafkaConsumerConfig.class);

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${nexuspay.schema-registry.url:}")
    private String schemaRegistryUrl;

    @Bean
    public ConsumerFactory<String, Map<String, Object>> batchConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, DualFormatDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
        if (schemaRegistryUrl != null && !schemaRegistryUrl.isBlank()) {
            props.put("schema.registry.url", schemaRegistryUrl);
        }
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Map<String, Object>> batchKafkaListenerContainerFactory(
            ConsumerFactory<String, Map<String, Object>> batchConsumerFactory,
            CommonErrorHandler kafkaErrorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, Map<String, Object>>();
        factory.setConsumerFactory(batchConsumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.setBatchListener(true);
        log.info("Batch Kafka consumer factory configured: batchSize=50");
        return factory;
    }
}
