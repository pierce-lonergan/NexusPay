package io.nexuspay.app.event;

import io.nexuspay.common.event.store.EventLog;
import io.nexuspay.common.event.store.EventLogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/**
 * Called from OutboxRelay after successful Kafka publish to capture the event
 * in the append-only event log for audit trail and debugging.
 *
 * <p>Controlled by the {@code nexuspay.event-log.enabled} flag.
 * Failures are logged but never propagate — the event log must not
 * interfere with the critical Kafka publishing path.
 */
@Component
public class EventLogAppender {

    private static final Logger log = LoggerFactory.getLogger(EventLogAppender.class);

    private final EventLog eventLog;
    private final boolean enabled;

    public EventLogAppender(EventLog eventLog,
                            @Value("${nexuspay.event-log.enabled:true}") boolean enabled) {
        this.eventLog = eventLog;
        this.enabled = enabled;
    }

    /**
     * Appends an event to the event log after successful Kafka publish.
     *
     * @param eventId       unique event ID
     * @param aggregateType aggregate type (e.g. "Payment")
     * @param aggregateId   aggregate instance ID
     * @param eventType     event type (e.g. "PaymentCaptured")
     * @param version       event schema version
     * @param jsonPayload   the JSON payload string
     * @param metadata      event metadata map
     * @param tenantId      tenant ID for RLS
     */
    public void append(String eventId, String aggregateType, String aggregateId,
                       String eventType, int version, String jsonPayload,
                       Map<String, String> metadata, String tenantId) {
        if (!enabled) return;

        try {
            var entry = new EventLogEntry(
                    eventId,
                    aggregateType,
                    aggregateId,
                    eventType,
                    version,
                    jsonPayload.getBytes(StandardCharsets.UTF_8),
                    EventLogEntry.PayloadFormat.JSON,
                    metadata,
                    tenantId != null ? tenantId : "unknown",
                    Instant.now()
            );
            eventLog.append(entry);
        } catch (Exception e) {
            // Never let event log failures affect the publishing pipeline
            log.error("Failed to append event to log: eventId={}, eventType={}", eventId, eventType, e);
        }
    }
}
