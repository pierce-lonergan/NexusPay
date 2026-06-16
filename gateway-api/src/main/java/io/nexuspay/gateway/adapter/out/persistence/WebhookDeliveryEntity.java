package io.nexuspay.gateway.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * INT-4: a persisted, at-least-once outbound webhook delivery — one row per (endpoint, logical event).
 *
 * <p>Recorded PENDING by {@code WebhookDeliveryService.recordDelivery} when a matching event is consumed,
 * then attempted via the shared SSRF-safe + canonical + signed {@code send} path. The outcome drives a
 * small state machine encapsulated here (mirroring {@link WebhookEndpointEntity#setEnabled}): a transient
 * failure schedules an exponential-backoff retry (FAILED + {@code next_attempt_at}); exhausting
 * {@code max_attempts} parks the row DEAD (a DLQ — never deleted); a 2xx marks it DELIVERED. The leader-
 * locked {@code WebhookDeliveryRetrier} re-drives FAILED rows whose {@code next_attempt_at} is due.</p>
 *
 * <p><strong>PCI/secret:</strong> {@code canonicalBody} holds only the exact envelope bytes already shipped
 * to the merchant (PAN-stripped by {@code WebhookEnvelopeSerializer}); the endpoint signing secret is NEVER
 * stored here — it is read live from {@code webhook_endpoints} per attempt, so a rotation takes effect on the
 * next attempt and a leaked deliveries row reveals neither a card number nor a signing key.</p>
 *
 * <p>The id is pre-assigned ({@link io.nexuspay.common.id.PrefixedId#webhookDelivery()}) — no
 * {@code @GeneratedValue}/{@code @Version} — so Spring Data {@code save()} does {@code merge()} and DEFERS
 * the INSERT; the recorder therefore uses {@code saveAndFlush} (L-041) to force the unique-index violation
 * synchronously inside its try/catch.</p>
 */
@Entity
@Table(name = "webhook_deliveries")
public class WebhookDeliveryEntity {

    /** Bounded so a verbose endpoint error never blows the {@code last_error VARCHAR(512)} column. */
    private static final int MAX_ERROR_LEN = 512;

    public enum Status {
        PENDING, DELIVERED, FAILED, DEAD
    }

    @Id
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "endpoint_id", nullable = false)
    private String endpointId;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    // TEXT (unbounded) to match the V4031 column — a plain String would map to varchar(255) and fail
    // ddl-auto=validate (L-025).
    @Column(name = "canonical_body", nullable = false, columnDefinition = "TEXT")
    private String canonicalBody;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 8;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_status_code")
    private Integer lastStatusCode;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected WebhookDeliveryEntity() {
    }

    private WebhookDeliveryEntity(String id, String tenantId, String endpointId, String eventId,
                                  String eventType, String canonicalBody) {
        this.id = id;
        this.tenantId = tenantId;
        this.endpointId = endpointId;
        this.eventId = eventId;
        this.eventType = eventType;
        this.canonicalBody = canonicalBody;
        this.status = Status.PENDING;
        this.attemptCount = 0;
        this.maxAttempts = 8;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Factory for a freshly-recorded PENDING delivery (attempt_count=0, default max_attempts=8). */
    public static WebhookDeliveryEntity pending(String id, String tenantId, String endpointId,
                                                String eventId, String eventType, String canonicalBody) {
        return new WebhookDeliveryEntity(id, tenantId, endpointId, eventId, eventType, canonicalBody);
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getEndpointId() { return endpointId; }
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getCanonicalBody() { return canonicalBody; }
    public Status getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Integer getLastStatusCode() { return lastStatusCode; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeliveredAt() { return deliveredAt; }

    // ---- state machine (mutations are the ONLY way to move status; mirrors WebhookEndpointEntity.setEnabled) ----

    /**
     * Records the attempt just made. Incremented by {@code recordOutcome} BEFORE deciding FAILED vs DEAD,
     * so {@code attemptCount} always counts attempts ALREADY MADE and the backoff/DEAD checks read a
     * 1-based count.
     */
    public void incrementAttempt() {
        this.attemptCount++;
        this.updatedAt = Instant.now();
    }

    /** 2xx — terminal success: clears the retry gate and stamps delivered_at. */
    public void markDelivered(int statusCode) {
        this.status = Status.DELIVERED;
        this.lastStatusCode = statusCode;
        this.nextAttemptAt = null;
        Instant now = Instant.now();
        this.deliveredAt = now;
        this.updatedAt = now;
    }

    /** Retryable failure: stays FAILED with a future {@code next_attempt_at} the retrier scans for. */
    public void markTransientFailure(Integer statusCode, String error, Instant nextAttemptAt) {
        this.status = Status.FAILED;
        this.lastStatusCode = statusCode;
        this.lastError = truncate(error);
        this.nextAttemptAt = nextAttemptAt;
        this.updatedAt = Instant.now();
    }

    /** Terminal failure (DLQ): permanent error, or transient retries exhausted. Never deleted. */
    public void markDead(Integer statusCode, String error) {
        this.status = Status.DEAD;
        this.lastStatusCode = statusCode;
        this.lastError = truncate(error);
        this.nextAttemptAt = null;
        this.updatedAt = Instant.now();
    }

    /**
     * Admin replay: re-arms an existing (typically DELIVERED or DEAD) row for one more attempt by flipping
     * it FAILED + immediately due. Deliberately does NOT reset {@code attempt_count} — a replay is a new
     * attempt on the SAME logical delivery, not a new event, so it keeps the backoff history but is always
     * immediately picked up by the next retrier due-scan. This is an UPDATE, never an INSERT, so the
     * {@code (endpoint_id, event_id)} uniqueness is untouched (that is how a replay differs from a duplicate).
     */
    public void requeueForReplay(Instant now) {
        this.status = Status.FAILED;
        this.nextAttemptAt = now;
        this.updatedAt = Instant.now();
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN);
    }
}
