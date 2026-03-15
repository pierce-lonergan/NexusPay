package io.nexuspay.dispute.domain;

import io.nexuspay.common.id.PrefixedId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Aggregate root for the dispute lifecycle.
 *
 * <p>Encapsulates the state machine governing dispute transitions:
 * <pre>
 *   OPENED → EVIDENCE_NEEDED → EVIDENCE_SUBMITTED → WON | LOST
 *                             → EXPIRED (deadline missed)
 * </pre>
 *
 * <p>Each state transition produces a {@link DisputeEvent} for the immutable
 * audit timeline.  Chargeback ledger entries are created externally by
 * {@link io.nexuspay.dispute.application.service.DisputeLifecycleService}
 * when transitions occur.</p>
 *
 * @since 0.2.4 (Sprint 2.4)
 */
public class Dispute {

    private String id;
    private String tenantId;
    private String paymentId;
    private String externalDisputeId;
    private String reasonCode;
    private String reasonDescription;
    private long amount;
    private String currency;
    private DisputeState status;
    private String network;
    private Instant evidenceDueDate;
    private Instant evidenceSubmittedAt;
    private Instant resolvedAt;
    private String outcome;
    private Instant createdAt;
    private Instant updatedAt;

    private final List<DisputeEvidence> evidence = new ArrayList<>();
    private final List<DisputeEvent> events = new ArrayList<>();

    public Dispute() {
    }

    // -- Factory --

    /**
     * Creates a new dispute from a PSP/network notification.
     */
    public static Dispute open(String tenantId, String paymentId, String externalDisputeId,
                               String reasonCode, String reasonDescription,
                               long amount, String currency, String network,
                               Instant evidenceDueDate) {
        Dispute d = new Dispute();
        d.id = PrefixedId.dispute();
        d.tenantId = tenantId;
        d.paymentId = paymentId;
        d.externalDisputeId = externalDisputeId;
        d.reasonCode = reasonCode;
        d.reasonDescription = reasonDescription;
        d.amount = amount;
        d.currency = currency;
        d.status = DisputeState.OPENED;
        d.network = network;
        d.evidenceDueDate = evidenceDueDate;
        d.createdAt = Instant.now();
        d.updatedAt = d.createdAt;

        d.events.add(DisputeEvent.transition(
                PrefixedId.disputeEvent(), d.id, tenantId,
                null, DisputeState.OPENED, "system",
                Map.of("reason_code", reasonCode, "network", network != null ? network : "unknown")
        ));
        return d;
    }

    // -- State transitions --

    /**
     * Transition to EVIDENCE_NEEDED (typically after initial assessment).
     */
    public void requestEvidence(String actor) {
        assertNotTerminal();
        assertState(DisputeState.OPENED);
        DisputeState old = this.status;
        this.status = DisputeState.EVIDENCE_NEEDED;
        this.updatedAt = Instant.now();
        this.events.add(DisputeEvent.transition(
                PrefixedId.disputeEvent(), id, tenantId, old, status, actor, Map.of()));
    }

    /**
     * Adds evidence and (if first upload on an OPENED dispute) transitions to EVIDENCE_NEEDED.
     */
    public void addEvidence(DisputeEvidence item) {
        assertNotTerminal();
        this.evidence.add(item);
        this.updatedAt = Instant.now();
        this.events.add(DisputeEvent.evidenceAction(
                PrefixedId.disputeEvent(), id, tenantId,
                "EVIDENCE_UPLOADED", "system",
                Map.of("evidence_type", item.getEvidenceType().name(), "file_name", item.getFileName())
        ));
    }

    /**
     * Submit all collected evidence to the card network.
     */
    public void submitEvidence(String actor) {
        assertNotTerminal();
        if (status != DisputeState.EVIDENCE_NEEDED && status != DisputeState.OPENED) {
            throw new IllegalStateException("Cannot submit evidence in state " + status);
        }
        DisputeState old = this.status;
        this.status = DisputeState.EVIDENCE_SUBMITTED;
        this.evidenceSubmittedAt = Instant.now();
        this.updatedAt = Instant.now();
        this.events.add(DisputeEvent.transition(
                PrefixedId.disputeEvent(), id, tenantId, old, status, actor,
                Map.of("evidence_count", evidence.size())
        ));
    }

    /**
     * Dispute won — funds reversed back to merchant.
     */
    public void win(String actor) {
        assertNotTerminal();
        DisputeState old = this.status;
        this.status = DisputeState.WON;
        this.outcome = "WON";
        this.resolvedAt = Instant.now();
        this.updatedAt = Instant.now();
        this.events.add(DisputeEvent.transition(
                PrefixedId.disputeEvent(), id, tenantId, old, status, actor, Map.of()));
    }

    /**
     * Dispute lost — chargeback finalized as expense.
     */
    public void lose(String actor) {
        assertNotTerminal();
        DisputeState old = this.status;
        this.status = DisputeState.LOST;
        this.outcome = "LOST";
        this.resolvedAt = Instant.now();
        this.updatedAt = Instant.now();
        this.events.add(DisputeEvent.transition(
                PrefixedId.disputeEvent(), id, tenantId, old, status, actor, Map.of()));
    }

    /**
     * Deadline expired without evidence submission.
     */
    public void expire() {
        if (status.isTerminal()) return; // idempotent — already resolved
        DisputeState old = this.status;
        this.status = DisputeState.EXPIRED;
        this.outcome = "EXPIRED";
        this.resolvedAt = Instant.now();
        this.updatedAt = Instant.now();
        this.events.add(DisputeEvent.transition(
                PrefixedId.disputeEvent(), id, tenantId, old, status, "system",
                Map.of("reason", "evidence_deadline_passed")
        ));
    }

    // -- Guards --

    private void assertNotTerminal() {
        if (status != null && status.isTerminal()) {
            throw new IllegalStateException("Dispute " + id + " is in terminal state " + status);
        }
    }

    private void assertState(DisputeState expected) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Expected state " + expected + " but was " + status + " for dispute " + id);
        }
    }

    // -- Getters & setters --

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }

    public String getExternalDisputeId() { return externalDisputeId; }
    public void setExternalDisputeId(String externalDisputeId) { this.externalDisputeId = externalDisputeId; }

    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }

    public String getReasonDescription() { return reasonDescription; }
    public void setReasonDescription(String reasonDescription) { this.reasonDescription = reasonDescription; }

    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public DisputeState getStatus() { return status; }
    public void setStatus(DisputeState status) { this.status = status; }

    public String getNetwork() { return network; }
    public void setNetwork(String network) { this.network = network; }

    public Instant getEvidenceDueDate() { return evidenceDueDate; }
    public void setEvidenceDueDate(Instant evidenceDueDate) { this.evidenceDueDate = evidenceDueDate; }

    public Instant getEvidenceSubmittedAt() { return evidenceSubmittedAt; }
    public void setEvidenceSubmittedAt(Instant evidenceSubmittedAt) { this.evidenceSubmittedAt = evidenceSubmittedAt; }

    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }

    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public List<DisputeEvidence> getEvidence() { return Collections.unmodifiableList(evidence); }
    public List<DisputeEvent> getEvents() { return Collections.unmodifiableList(events); }
}
