package io.nexuspay.common.event.store;

import java.time.Instant;
import java.util.List;

/**
 * Port interface for the append-only event log.
 * Implementations persist published events for audit trail and debugging.
 *
 * <p>This is intentionally simple — no snapshots, no sequence numbers,
 * no aggregate replay. Those capabilities will be added in Phase 4
 * if/when event-sourced aggregates are needed.
 */
public interface EventLog {

    /**
     * Append an event to the log. Idempotent by event_id (UNIQUE constraint).
     *
     * @param entry the event log entry to persist
     */
    void append(EventLogEntry entry);

    /**
     * Find all events for a specific aggregate instance, ordered by creation time.
     *
     * @param aggregateType the aggregate type (e.g. "Payment")
     * @param aggregateId   the aggregate instance ID
     * @return ordered list of event log entries
     */
    List<EventLogEntry> findByAggregate(String aggregateType, String aggregateId);

    /**
     * Find events by type within a time range, with pagination limit.
     *
     * @param eventType the event type to filter by
     * @param after     only return events created after this instant
     * @param limit     maximum number of entries to return
     * @return ordered list of event log entries
     */
    List<EventLogEntry> findByEventType(String eventType, Instant after, int limit);
}
