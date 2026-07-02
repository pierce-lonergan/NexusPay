package io.nexuspay.payment.adapter.in.webhook;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
    @JdbcTypeCode(SqlTypes.JSON)
    private String rawPayload;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    /**
     * GAP-015: server-authoritative owning tenant, persisted since V1301 ({@code NOT NULL DEFAULT 'default'})
     * but never surfaced by this entity until now. The LIVE webhook path does NOT stamp this at persist time —
     * the tenant is resolved later at outbox-write from {@code ScreeningOriginService.find(paymentId)} (SEC-09),
     * so RECEIVED rows carry the DB default 'default'. Mapped {@code insertable=false, updatable=false} (READ-ONLY):
     * Hibernate OMITS the column from INSERT/UPDATE so the DB default governs on insert — a plain mapped-but-null
     * field would instead emit {@code tenant_id=NULL} and violate the NOT NULL column (which broke the inbound
     * dedup guard). The reprocess path resolves tenant the SAME way the live path does (via the origin store),
     * NEVER by trusting this column.
     */
    @Column(name = "tenant_id", insertable = false, updatable = false)
    private String tenantId;

    /**
     * GAP-015 (V4045): audit stamp of an operator-initiated reprocess (FAILED -> PROCESSED via
     * {@code POST /v1/admin/webhooks/reprocess/{id}}). NULL on rows that reached PROCESSED via the
     * normal live path; set by {@link #markReprocessed()} only on a successful reprocess.
     */
    @Column(name = "reprocessed_at")
    private Instant reprocessedAt;

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

    /**
     * GAP-015: marks a FAILED inbound webhook PROCESSED after an operator reprocess re-drove its outbox
     * write. Sets the {@code reprocessed_at} audit stamp and flips status to PROCESSED (mirrors
     * {@link #markProcessed()}). Called INSIDE the reprocess {@code @Transactional} AFTER the outbox
     * re-insert, so the status flip and the outbox row commit atomically — a re-insert failure rolls the
     * flip back and the row stays FAILED, re-drivable.
     */
    public void markReprocessed() {
        this.reprocessedAt = Instant.now();
        this.processedAt = Instant.now();
        this.status = STATUS_PROCESSED;
    }

    // Getters
    public String getId() { return id; }
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getRawPayload() { return rawPayload; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getProcessedAt() { return processedAt; }
    public String getTenantId() { return tenantId; }
    public Instant getReprocessedAt() { return reprocessedAt; }
    public String getStatus() { return status; }
}
