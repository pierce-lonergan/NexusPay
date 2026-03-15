package io.nexuspay.payment.adapter.in.webhook;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Persistent record of every inbound webhook received from HyperSwitch.
 * Raw payload stored for debugging and replay capability.
 *
 * Lifecycle: RECEIVED → PROCESSED | FAILED
 */
@Entity
@Table(name = "inbound_webhooks")
public class InboundWebhook {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "event_id", unique = true, nullable = false, length = 128)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "raw_payload", nullable = false, columnDefinition = "jsonb")
    private String rawPayload;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(nullable = false, length = 16)
    private String status;

    public static final String STATUS_RECEIVED = "RECEIVED";
    public static final String STATUS_PROCESSED = "PROCESSED";
    public static final String STATUS_FAILED = "FAILED";

    protected InboundWebhook() {
    }

    public InboundWebhook(String id, String eventId, String eventType, String rawPayload) {
        this.id = id;
        this.eventId = eventId;
        this.eventType = eventType;
        this.rawPayload = rawPayload;
        this.receivedAt = Instant.now();
        this.status = STATUS_RECEIVED;
    }

    public void markProcessed() {
        this.processedAt = Instant.now();
        this.status = STATUS_PROCESSED;
    }

    public void markFailed() {
        this.processedAt = Instant.now();
        this.status = STATUS_FAILED;
    }

    // Getters
    public String getId() { return id; }
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getRawPayload() { return rawPayload; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getProcessedAt() { return processedAt; }
    public String getStatus() { return status; }
}
