package io.nexuspay.payment.adapter.out.outbox;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Transactional outbox event.
 *
 * Written in the same database transaction as the business state change.
 * The OutboxRelay polls for unpublished events and publishes them to Kafka.
 *
 * This pattern guarantees that events are only published if the business
 * transaction commits. If Kafka is unavailable, events accumulate in the
 * outbox table and are delivered when Kafka recovers.
 */
@Entity
@Table(name = "event_outbox",
        indexes = @Index(name = "idx_outbox_unpublished",
                columnList = "created_at",
                unique = false))
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
    }

    public OutboxEvent(String aggregateType, String aggregateId, String eventType, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    // Getters
    public Long getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
}
