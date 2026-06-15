package io.nexuspay.dispute.application.port.out;

import io.nexuspay.dispute.domain.Dispute;
import io.nexuspay.dispute.domain.DisputeEvidence;
import io.nexuspay.dispute.domain.DisputeEvent;

import java.util.List;
import java.util.Optional;

/**
 * Output port for dispute persistence.
 *
 * @since 0.2.4 (Sprint 2.4)
 */
public interface DisputeRepository {

    Dispute save(Dispute dispute);

    Optional<Dispute> findById(String id);

    /**
     * Idempotency lookup for the dispute webhook: an inbound dispute event is
     * uniquely keyed by (tenantId, externalDisputeId). Used by
     * {@code DisputeLifecycleService.openDispute} to no-op a replay/redelivery
     * instead of re-posting the chargeback reserve (SEC-BATCH-2 / SEC-01).
     */
    Optional<Dispute> findByTenantIdAndExternalDisputeId(String tenantId, String externalDisputeId);

    List<Dispute> findByTenant(String tenantId, int limit, int offset);

    List<Dispute> findByPaymentId(String paymentId);

    List<Dispute> findByStatus(String tenantId, String status, int limit, int offset);

    // -- Evidence --

    DisputeEvidence saveEvidence(DisputeEvidence evidence);

    List<DisputeEvidence> findEvidenceByDisputeId(String disputeId);

    // -- Events --

    DisputeEvent saveEvent(DisputeEvent event);

    List<DisputeEvent> findEventsByDisputeId(String disputeId);
}
