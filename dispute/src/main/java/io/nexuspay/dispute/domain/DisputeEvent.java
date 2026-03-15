package io.nexuspay.dispute.domain;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable event recording a state transition or action on a dispute.
 *
 * <p>Events form a chronological audit timeline attached to each dispute,
 * persisted in the {@code dispute_events} table.</p>
 *
 * @since 0.2.4 (Sprint 2.4)
 */
public class DisputeEvent {

    private String id;
    private String disputeId;
    private String tenantId;
    private String eventType;
    private DisputeState oldStatus;
    private DisputeState newStatus;
    private String actor;
    private Map<String, Object> details;
    private Instant createdAt;

    public DisputeEvent() {
    }

    /**
     * Creates a state-transition event.
     */
    public static DisputeEvent transition(String id, String disputeId, String tenantId,
                                          DisputeState oldStatus, DisputeState newStatus,
                                          String actor, Map<String, Object> details) {
        DisputeEvent event = new DisputeEvent();
        event.id = id;
        event.disputeId = disputeId;
        event.tenantId = tenantId;
        event.eventType = "STATE_TRANSITION";
        event.oldStatus = oldStatus;
        event.newStatus = newStatus;
        event.actor = actor;
        event.details = details;
        event.createdAt = Instant.now();
        return event;
    }

    /**
     * Creates an evidence-related event.
     */
    public static DisputeEvent evidenceAction(String id, String disputeId, String tenantId,
                                               String eventType, String actor,
                                               Map<String, Object> details) {
        DisputeEvent event = new DisputeEvent();
        event.id = id;
        event.disputeId = disputeId;
        event.tenantId = tenantId;
        event.eventType = eventType;
        event.actor = actor;
        event.details = details;
        event.createdAt = Instant.now();
        return event;
    }

    // -- Getters & setters --

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDisputeId() { return disputeId; }
    public void setDisputeId(String disputeId) { this.disputeId = disputeId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public DisputeState getOldStatus() { return oldStatus; }
    public void setOldStatus(DisputeState oldStatus) { this.oldStatus = oldStatus; }

    public DisputeState getNewStatus() { return newStatus; }
    public void setNewStatus(DisputeState newStatus) { this.newStatus = newStatus; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    public Map<String, Object> getDetails() { return details; }
    public void setDetails(Map<String, Object> details) { this.details = details; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
