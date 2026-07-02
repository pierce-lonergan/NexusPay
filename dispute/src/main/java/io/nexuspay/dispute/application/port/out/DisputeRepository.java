package io.nexuspay.dispute.application.port.out;

import io.nexuspay.dispute.domain.Dispute;
import io.nexuspay.dispute.domain.DisputeEvidence;
import io.nexuspay.dispute.domain.DisputeEvent;

import java.time.Instant;
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
     * SEC-27: tenant-scoped by-id lookup for REST reads/mutations. Returns the dispute only when it
     * belongs to {@code tenantId}; an absent OR foreign-tenant dispute yields an empty Optional so the
     * caller cannot distinguish "does not exist" from "belongs to another tenant" (no cross-tenant
     * existence oracle). The predicate is pushed to SQL so a foreign-tenant row never leaves the DB.
     *
     * <p>This is the controller-side control: {@code DisputeController} resolves the tenant from the
     * authenticated principal ({@code CallerTenant.require()}) and routes every by-id read/mutation
     * through this finder. The unscoped {@link #findById(String)} remains for the
     * SERVER-AUTHORITATIVE webhook path ({@code DisputeWebhookHandler} → lifecycle win/lose/expire),
     * which has no REST caller-tenant and is already SEC-2-hardened.</p>
     */
    Optional<Dispute> findByIdAndTenantId(String id, String tenantId);

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

    /**
     * GAP-033: CROSS-TENANT discovery finder for the evidence-deadline expiry sweep. Returns disputes
     * whose evidence deadline has passed AND that are still in a PRE-TERMINAL EVIDENCE state
     * ({@code OPENED} or {@code EVIDENCE_NEEDED}), i.e. genuinely overdue with no response.
     *
     * <p>Predicate: {@code evidence_due_date < now AND status IN ('OPENED','EVIDENCE_NEEDED')}, ordered
     * by {@code evidence_due_date} ASC for determinism, bounded by {@code limit} so a large backlog
     * cannot OOM the sweep.</p>
     *
     * <p>DELIBERATELY EXCLUDES:
     * <ul>
     *   <li>{@code EVIDENCE_SUBMITTED} — evidence is already with the network awaiting a WON/LOST
     *       decision; force-expiring it would finalise a chargeback the merchant is actively defending;</li>
     *   <li>the terminal set {@code WON/LOST/EXPIRED} — already resolved (a re-selected terminal dispute
     *       would double-post the chargeback expense).</li>
     * </ul>
     *
     * <p>Takes NO tenantId argument because the sweep runs as SYSTEM ({@code @SystemTransactional}) and
     * must see EVERY tenant's overdue disputes; each per-dispute expire is then bound to that dispute's
     * OWN tenant via {@code TenantWorkRunner} so RLS WITH CHECK scopes the write on activation.</p>
     */
    List<Dispute> findExpirable(Instant now, int limit);

    // -- Evidence --

    DisputeEvidence saveEvidence(DisputeEvidence evidence);

    List<DisputeEvidence> findEvidenceByDisputeId(String disputeId);

    // -- Events --

    DisputeEvent saveEvent(DisputeEvent event);

    List<DisputeEvent> findEventsByDisputeId(String disputeId);

    /**
     * SEC-27: tenant-scoped event-timeline lookup for the REST {@code GET /v1/disputes/{id}/events}
     * endpoint. Filters the timeline to {@code tenantId} in SQL so a tenant-A caller cannot read the
     * event history of a tenant-B dispute by id. The {@code dispute_events} rows carry their own
     * {@code tenant_id} (denormalised from the parent dispute), so an empty list means "no such
     * dispute for this tenant" — consistent with the no-oracle 404 the controller returns after the
     * scoped by-id ownership check.
     */
    List<DisputeEvent> findEventsByDisputeIdAndTenantId(String disputeId, String tenantId);
}
