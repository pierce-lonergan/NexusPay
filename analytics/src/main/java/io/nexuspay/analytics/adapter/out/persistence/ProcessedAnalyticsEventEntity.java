package io.nexuspay.analytics.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * JPA entity for the {@code analytics.processed_analytics_events} dedup table (SEC-18, V4033).
 *
 * <p>One marker row per {@code (event_id, rollup_kind)}. Each additive rollup consumer claims a
 * marker BEFORE its additive upsert (via {@link ProcessedEventRepositoryAdapter#markProcessed}'s
 * native {@code INSERT ... ON CONFLICT DO NOTHING}) and skips the upsert when the marker already
 * exists, so a Kafka redelivery / DLT replay cannot double-count. The dedup table IS its own natural
 * key (no surrogate id) — composite {@code @IdClass} on {@code (eventId, rollupKind)}.</p>
 *
 * <p>This entity exists so Hibernate {@code ddl-auto: validate} verifies the V4033 table mapping at
 * boot; the marker writes/reads themselves go through the native adapter (single-statement upsert),
 * not a Spring Data repository.</p>
 *
 * @since SEC-18
 */
@Entity
@Table(name = "processed_analytics_events", schema = "analytics")
@IdClass(ProcessedAnalyticsEventEntity.Key.class)
public class ProcessedAnalyticsEventEntity {

    @Id
    @Column(name = "event_id", length = 128)
    private String eventId;

    @Id
    @Column(name = "rollup_kind", length = 40)
    private String rollupKind;

    @Column(name = "tenant_id", nullable = false, length = 36)
    private String tenantId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProcessedAnalyticsEventEntity() {
    }

    public ProcessedAnalyticsEventEntity(String eventId, String rollupKind,
                                         String tenantId, Instant createdAt) {
        this.eventId = eventId;
        this.rollupKind = rollupKind;
        this.tenantId = tenantId;
        this.createdAt = createdAt;
    }

    public String getEventId() {
        return eventId;
    }

    public String getRollupKind() {
        return rollupKind;
    }

    public String getTenantId() {
        return tenantId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Composite primary key for {@link ProcessedAnalyticsEventEntity}: the natural dedup key. */
    public static class Key implements Serializable {

        private String eventId;
        private String rollupKind;

        public Key() {
        }

        public Key(String eventId, String rollupKind) {
            this.eventId = eventId;
            this.rollupKind = rollupKind;
        }

        public String getEventId() {
            return eventId;
        }

        public String getRollupKind() {
            return rollupKind;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(eventId, key.eventId)
                    && Objects.equals(rollupKind, key.rollupKind);
        }

        @Override
        public int hashCode() {
            return Objects.hash(eventId, rollupKind);
        }
    }
}
