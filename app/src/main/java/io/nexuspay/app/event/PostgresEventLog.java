package io.nexuspay.app.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.event.store.EventLog;
import io.nexuspay.common.event.store.EventLogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL implementation of the {@link EventLog} port.
 * Simple JPA-based append-only persistence — no locking, no snapshots.
 */
@Component
public class PostgresEventLog implements EventLog {

    private static final Logger log = LoggerFactory.getLogger(PostgresEventLog.class);

    private final JpaEventLogRepository repository;
    private final ObjectMapper objectMapper;

    public PostgresEventLog(JpaEventLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(EventLogEntry entry) {
        // Idempotent: skip if event_id already exists (UNIQUE constraint also enforces this)
        if (repository.existsByEventId(entry.eventId())) {
            log.debug("Event already logged, skipping: {}", entry.eventId());
            return;
        }

        String metadataJson = serializeMetadata(entry.metadata());
        var entity = new EventLogEntity(
                entry.eventId(),
                entry.aggregateType(),
                entry.aggregateId(),
                entry.eventType(),
                entry.eventVersion(),
                entry.payload(),
                entry.payloadFormat().name(),
                metadataJson,
                entry.tenantId()
        );

        repository.save(entity);
        log.debug("Appended event to log: type={}, id={}", entry.eventType(), entry.eventId());
    }

    @Override
    public List<EventLogEntry> findByAggregate(String aggregateType, String aggregateId) {
        return repository.findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(aggregateType, aggregateId)
                .stream()
                .map(this::toEntry)
                .toList();
    }

    @Override
    public List<EventLogEntry> findByEventType(String eventType, Instant after, int limit) {
        return repository.findByEventTypeAfter(eventType, after, PageRequest.of(0, limit))
                .stream()
                .map(this::toEntry)
                .toList();
    }

    private EventLogEntry toEntry(EventLogEntity entity) {
        return new EventLogEntry(
                entity.getEventId(),
                entity.getAggregateType(),
                entity.getAggregateId(),
                entity.getEventType(),
                entity.getEventVersion(),
                entity.getPayload(),
                EventLogEntry.PayloadFormat.valueOf(entity.getPayloadFormat()),
                deserializeMetadata(entity.getMetadata()),
                entity.getTenantId(),
                entity.getCreatedAt()
        );
    }

    private String serializeMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize event metadata, storing null", e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> deserializeMetadata(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize event metadata", e);
            return Map.of();
        }
    }
}
