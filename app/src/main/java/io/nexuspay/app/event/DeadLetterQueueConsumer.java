package io.nexuspay.app.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.event.Topics;
import io.nexuspay.common.event.dlq.DeadLetterStatus;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Kafka listener that consumes from all dead letter topics (.DLT) and persists
 * failed events to the {@code dead_letter_queue} table for retry and manual resolution.
 *
 * <p>Extracts error information from Spring Kafka's DLT headers
 * ({@code kafka_dlt-exception-fqcn}, {@code kafka_dlt-exception-message}, etc.).
 */
@Component
public class DeadLetterQueueConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterQueueConsumer.class);

    private static final String DLT_EXCEPTION_FQCN = "kafka_dlt-exception-fqcn";
    private static final String DLT_EXCEPTION_MESSAGE = "kafka_dlt-exception-message";
    private static final String DLT_EXCEPTION_STACKTRACE = "kafka_dlt-exception-stacktrace";
    private static final String DLT_ORIGINAL_TOPIC = "kafka_dlt-original-topic";
    private static final String DLT_ORIGINAL_PARTITION = "kafka_dlt-original-partition";
    private static final String DLT_ORIGINAL_OFFSET = "kafka_dlt-original-offset";

    private final DeadLetterRepository repository;
    private final ObjectMapper objectMapper;

    public DeadLetterQueueConsumer(DeadLetterRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = {
                    Topics.PAYMENTS_DLT,
                    Topics.LEDGER_DLT,
                    Topics.BILLING_DLT,
                    Topics.FRAUD_DLT,
                    Topics.FX_DLT,
                    Topics.ROUTING_DLT
            },
            groupId = "nexuspay-dlq-consumer",
            containerFactory = "dltKafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record) {
        try {
            log.info("DLQ consumer received record from topic={}, key={}", record.topic(), record.key());

            var entry = new DeadLetterEntry();

            // Original topic info from DLT headers (falls back to DLT topic minus ".DLT" suffix)
            String originalTopic = extractHeader(record, DLT_ORIGINAL_TOPIC);
            if (originalTopic == null && record.topic().endsWith(".DLT")) {
                originalTopic = record.topic().substring(0, record.topic().length() - 4);
            }
            entry.setOriginalTopic(originalTopic != null ? originalTopic : record.topic());

            // Original partition/offset from DLT headers
            String partStr = extractHeader(record, DLT_ORIGINAL_PARTITION);
            if (partStr != null) {
                try { entry.setOriginalPartition(Integer.parseInt(partStr)); }
                catch (NumberFormatException ignored) {}
            }
            String offStr = extractHeader(record, DLT_ORIGINAL_OFFSET);
            if (offStr != null) {
                try { entry.setOriginalOffset(Long.parseLong(offStr)); }
                catch (NumberFormatException ignored) {}
            }

            entry.setEventKey(record.key());
            entry.setEventValue(record.value());
            entry.setEventHeaders(serializeHeaders(record));

            // Error info from Spring Kafka DLT headers
            entry.setExceptionClass(extractHeader(record, DLT_EXCEPTION_FQCN));
            entry.setErrorMessage(truncate(extractHeader(record, DLT_EXCEPTION_MESSAGE), 2000));
            entry.setStackTrace(extractHeader(record, DLT_EXCEPTION_STACKTRACE));

            // Try to extract tenant_id from the event value
            entry.setTenantId(extractTenantId(record.value()));

            entry.setStatus(DeadLetterStatus.PENDING);

            repository.save(entry);
            log.info("Persisted DLQ entry: id={}, topic={}, key={}", entry.getId(), entry.getOriginalTopic(), entry.getEventKey());

        } catch (Exception e) {
            log.error("Failed to persist DLQ entry from topic={}, key={}",
                    record.topic(), record.key(), e);
        }
    }

    private String extractHeader(ConsumerRecord<?, ?> record, String headerName) {
        Header header = record.headers().lastHeader(headerName);
        if (header == null) return null;
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private String serializeHeaders(ConsumerRecord<?, ?> record) {
        try {
            Map<String, String> headers = new HashMap<>();
            record.headers().forEach(h ->
                    headers.put(h.key(), new String(h.value(), StandardCharsets.UTF_8)));
            return objectMapper.writeValueAsString(headers);
        } catch (Exception e) {
            log.warn("Failed to serialize DLT record headers", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String extractTenantId(String eventValue) {
        if (eventValue == null) return "unknown";
        try {
            Map<String, Object> map = objectMapper.readValue(eventValue, Map.class);
            Object metadata = map.get("metadata");
            if (metadata instanceof Map<?, ?> metaMap) {
                Object tenantId = metaMap.get("tenant_id");
                if (tenantId != null) return tenantId.toString();
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    private String truncate(String s, int maxLength) {
        if (s == null) return null;
        return s.length() <= maxLength ? s : s.substring(0, maxLength);
    }
}
