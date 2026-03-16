package io.nexuspay.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.app.event.EventLogAppender;
import io.nexuspay.common.event.avro.DualWritePublisher;
import io.nexuspay.payment.adapter.out.outbox.OutboxEvent;
import io.nexuspay.payment.adapter.out.outbox.PostPublishCallback;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Wires together the dual-write publisher and event log appender with the OutboxRelay.
 *
 * <p>Creates the {@link DualWritePublisher} bean backed by the existing JSON KafkaTemplate
 * and a {@link PostPublishCallback} that delegates to the {@link EventLogAppender}.
 */
@Configuration
public class DualWriteConfig {

    private static final Logger log = LoggerFactory.getLogger(DualWriteConfig.class);

    @Value("${nexuspay.avro.dual-write.enabled:false}")
    private boolean dualWriteEnabled;

    @Bean
    public DualWritePublisher dualWritePublisher(KafkaTemplate<String, String> stringKafkaTemplate,
                                                  ObjectMapper objectMapper) {
        log.info("Configuring DualWritePublisher: dual-write={}", dualWriteEnabled);

        DualWritePublisher.KafkaSender<String, String> jsonSender = record ->
                stringKafkaTemplate.send(record)
                        .thenApply(result -> null)
                        .toCompletableFuture();

        // Avro sender is null for now — Avro-as-value publishing will be wired
        // when Schema Registry serialization is fully integrated
        DualWritePublisher.KafkaSender<String, byte[]> avroSender = record ->
                CompletableFuture.completedFuture(null);

        return new DualWritePublisher(dualWriteEnabled, jsonSender, avroSender, objectMapper);
    }

    @Bean
    public PostPublishCallback postPublishCallback(EventLogAppender eventLogAppender,
                                                    ObjectMapper objectMapper) {
        return event -> {
            try {
                // Extract metadata from the JSON payload for the event log
                @SuppressWarnings("unchecked")
                Map<String, Object> payloadMap = objectMapper.readValue(event.getPayload(), Map.class);
                @SuppressWarnings("unchecked")
                Map<String, String> metadata = payloadMap.containsKey("metadata")
                        ? (Map<String, String>) payloadMap.get("metadata") : Map.of();

                String eventId = payloadMap.containsKey("event_id")
                        ? String.valueOf(payloadMap.get("event_id"))
                        : "outbox-" + event.getId();

                eventLogAppender.append(
                        eventId,
                        event.getAggregateType(),
                        event.getAggregateId(),
                        event.getEventType(),
                        event.getEventVersion(),
                        event.getPayload(),
                        metadata,
                        event.getTenantId()
                );
            } catch (Exception e) {
                // Never let event log failures affect the publish pipeline
                log.error("Failed to append event to log after publish: outboxId={}", event.getId(), e);
            }
        };
    }
}
