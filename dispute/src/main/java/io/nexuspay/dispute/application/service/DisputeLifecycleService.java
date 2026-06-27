package io.nexuspay.dispute.application.service;

import io.nexuspay.common.event.EventTypes;
import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.common.tenant.TenantOwnership;
import io.nexuspay.dispute.application.port.out.DisputeOutboxPort;
import io.nexuspay.dispute.application.port.out.DisputeRepository;
import io.nexuspay.dispute.application.port.out.EvidenceStoragePort;
import io.nexuspay.dispute.application.port.out.LedgerPort;
import io.nexuspay.dispute.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Core service managing the dispute lifecycle and chargeback ledger integration.
 *
 * <p>Orchestrates state transitions, evidence collection, and the corresponding
 * double-entry ledger entries for each phase of the dispute.</p>
 *
 * <h3>Chargeback Ledger Entries</h3>
 * <ul>
 *   <li><b>OPENED</b>: DR chargeback_reserve, CR merchant_receivables (reserve funds)</li>
 *   <li><b>WON</b>: DR merchant_receivables, CR chargeback_reserve (reverse reservation)</li>
 *   <li><b>LOST</b>: DR chargeback_expense, CR chargeback_reserve (finalise loss)</li>
 * </ul>
 *
 * @since 0.2.4 (Sprint 2.4)
 */
@Service
public class DisputeLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(DisputeLifecycleService.class);

    private final DisputeRepository disputeRepository;
    private final EvidenceStoragePort evidenceStorage;
    private final LedgerPort ledgerPort;
    private final DisputeOutboxPort outboxPort;

    public DisputeLifecycleService(DisputeRepository disputeRepository,
                                    EvidenceStoragePort evidenceStorage,
                                    LedgerPort ledgerPort,
                                    DisputeOutboxPort outboxPort) {
        this.disputeRepository = disputeRepository;
        this.evidenceStorage = evidenceStorage;
        this.ledgerPort = ledgerPort;
        this.outboxPort = outboxPort;
    }

    /**
     * Opens a new dispute, creates chargeback reserve ledger entry.
     *
     * <p>IDEMPOTENT on {@code (tenantId, externalDisputeId)} (SEC-BATCH-2 / SEC-01):
     * a PSP redelivery or a replayed webhook for an already-opened dispute is a
     * no-op — it returns the existing dispute WITHOUT minting a second dispute or
     * re-posting the chargeback reserve (the money-moving line below). This mirrors
     * the transition-guard precedent in {@link #expire} (post to the ledger only
     * when a real change occurred). The {@code uq_disputes_tenant_external} UNIQUE
     * constraint (Flyway V4026) is the in-transaction race backstop for two
     * concurrent first-deliveries; because it is enforced at COMMIT inside this
     * same {@code @Transactional} as the ledger posting, a rolled-back transaction
     * leaves no durable suppression mark and a legitimate retry stays
     * reprocessable (avoids the B-015 pre-commit-mark-vs-rollback defect).</p>
     */
    @Transactional
    public Dispute openDispute(String tenantId, String paymentId, String externalDisputeId,
                               String reasonCode, String reasonDescription,
                               long amount, String currency, String network,
                               Instant evidenceDueDate) {
        // Real chargeback (webhook / network) path -> livemode=true.
        return openDispute(tenantId, paymentId, externalDisputeId, reasonCode, reasonDescription,
                amount, currency, network, evidenceDueDate, true);
    }

    /**
     * TEST-2: open carrying the event's key MODE. A real chargeback is {@code livemode=true}; the
     * test-mode dispute simulator ({@code POST /v1/test/disputes}) passes {@code livemode=false} so the
     * emitted {@code dispute.*} webhooks are stamped TEST. The mapped outbound events are emitted IN THE
     * SAME TRANSACTION as the state change + the chargeback reserve (transactional outbox): on the open
     * we emit {@code dispute.created}, then {@code dispute.funds_withdrawn} right after the reserve is
     * posted (the money-moving point) so a credit-ledger integrator can reverse the granted credit.
     */
    @Transactional
    public Dispute openDispute(String tenantId, String paymentId, String externalDisputeId,
                               String reasonCode, String reasonDescription,
                               long amount, String currency, String network,
                               Instant evidenceDueDate, boolean livemode) {

        // Idempotency guard: a dispute is uniquely identified by (tenant,
        // externalDisputeId). On a replay, no-op and return the existing dispute
        // so the chargeback reserve is posted EXACTLY ONCE — and, just as
        // importantly, NO duplicate dispute.* webhook is emitted on the replay.
        if (externalDisputeId != null && !externalDisputeId.isBlank()) {
            Optional<Dispute> existing =
                    disputeRepository.findByTenantIdAndExternalDisputeId(tenantId, externalDisputeId);
            if (existing.isPresent()) {
                Dispute prior = existing.get();
                log.info("duplicate dispute.opened ignored: tenant={}, external_id={}, existing_dispute={}",
                        tenantId, externalDisputeId, prior.getId());
                return prior;
            }
        }

        Dispute dispute = Dispute.open(tenantId, paymentId, externalDisputeId,
                reasonCode, reasonDescription, amount, currency, network, evidenceDueDate);

        dispute = disputeRepository.save(dispute);

        // Persist all domain events generated by the factory
        for (DisputeEvent event : dispute.getEvents()) {
            disputeRepository.saveEvent(event);
        }

        // TEST-2: emit dispute.created BEFORE the reserve — the dispute now exists.
        publish(dispute, EventTypes.DISPUTE_CREATED, livemode);

        // Create chargeback reserve in ledger under the dispute's server-authoritative
        // tenant (SEC-24) — the same tenantId used for the dispute idempotency key above.
        ledgerPort.createChargebackReserve(tenantId, dispute.getId(), amount, currency);

        // TEST-2: emit dispute.funds_withdrawn AT the chargeback-reserve point — this is the event a
        // credit-ledger integrator listens for to reverse the credit it granted (the over-grant fix).
        publish(dispute, EventTypes.DISPUTE_FUNDS_WITHDRAWN, livemode);

        log.info("Dispute opened: id={}, payment={}, amount={} {}, network={}, livemode={}",
                dispute.getId(), paymentId, amount, currency, network, livemode);

        return dispute;
    }

    /**
     * Transitions dispute to EVIDENCE_NEEDED.
     */
    @Transactional
    public Dispute requestEvidence(String disputeId, String actor) {
        Dispute dispute = getOrThrow(disputeId);
        int eventsBefore = dispute.getEvents().size();
        dispute.requestEvidence(actor);
        dispute = disputeRepository.save(dispute);
        saveNewEvents(dispute, eventsBefore);
        // TEST-2: evidence-needed -> dispute.evidence_needed (in the same tx as the transition).
        publish(dispute, EventTypes.DISPUTE_EVIDENCE_NEEDED, true);
        log.info("Evidence requested: dispute={}", disputeId);
        return dispute;
    }

    /**
     * Uploads evidence file and attaches it to the dispute.
     */
    @Transactional
    public DisputeEvidence uploadEvidence(String disputeId, String tenantId,
                                          DisputeEvidenceType type, String fileName,
                                          InputStream content, String contentType,
                                          String description) {

        // SEC-27: resolve the dispute through the tenant-scoped finder so a tenant-A caller cannot
        // attach evidence to (or even confirm the existence of) a tenant-B dispute. The {@code tenantId}
        // here is the authenticated caller's tenant (CallerTenant.require() at the controller), never a
        // client X-Tenant-Id header. Absent OR foreign -> 404, BEFORE anything is stored.
        Dispute dispute = getOrThrow(disputeId, tenantId);

        // Store file in object storage
        String fileKey = evidenceStorage.store(tenantId, disputeId, fileName, content, contentType);

        DisputeEvidence evidence = new DisputeEvidence(
                PrefixedId.disputeEvidence(), disputeId, tenantId,
                type, fileKey, fileName, null, description
        );

        int eventsBefore = dispute.getEvents().size();
        dispute.addEvidence(evidence);
        disputeRepository.save(dispute);
        saveNewEvents(dispute, eventsBefore);

        evidence = disputeRepository.saveEvidence(evidence);

        log.info("Evidence uploaded: dispute={}, type={}, file={}",
                disputeId, type, fileName);

        return evidence;
    }

    /**
     * Submits collected evidence (marks dispute as EVIDENCE_SUBMITTED).
     *
     * <p>UNSCOPED webhook/internal path: invoked by {@code DisputeWebhookHandler} (no REST
     * caller-tenant; the dispute is resolved server-side) and by {@code AutoRepresentmentService}
     * (triggered from the SEC-2-hardened webhook). The REST controller uses the tenant-scoped
     * {@link #submitEvidence(String, String, String)} overload instead.</p>
     */
    @Transactional
    public Dispute submitEvidence(String disputeId, String actor) {
        Dispute dispute = getOrThrow(disputeId);
        int eventsBefore = dispute.getEvents().size();
        dispute.submitEvidence(actor);
        dispute = disputeRepository.save(dispute);
        saveNewEvents(dispute, eventsBefore);
        // TEST-2: evidence-submitted -> dispute.evidence_submitted (same tx as the transition).
        // submitEvidence is a real transition on every successful call (the domain throws on an invalid
        // state rather than no-op'ing), so this is the merchant's "evidence accepted + represented to the
        // network" signal between dispute.evidence_needed and dispute.won/lost — the previously silent node.
        publish(dispute, EventTypes.DISPUTE_EVIDENCE_SUBMITTED, true);
        log.info("Evidence submitted: dispute={}", disputeId);
        return dispute;
    }

    /**
     * SEC-27: tenant-scoped evidence submission for the REST {@code POST /v1/disputes/{id}/submit}
     * endpoint. Resolves the dispute through the tenant-scoped finder + {@link TenantOwnership#require}
     * so a tenant-A caller cannot submit evidence on (or probe the existence of) a tenant-B dispute —
     * a foreign/absent id 404s (no oracle) BEFORE any state transition. {@code tenantId} is the
     * authenticated caller's tenant (CallerTenant.require()), never a client header.
     */
    @Transactional
    public Dispute submitEvidence(String disputeId, String tenantId, String actor) {
        Dispute dispute = getOrThrow(disputeId, tenantId);
        int eventsBefore = dispute.getEvents().size();
        dispute.submitEvidence(actor);
        dispute = disputeRepository.save(dispute);
        saveNewEvents(dispute, eventsBefore);
        // TEST-2: evidence-submitted -> dispute.evidence_submitted (same tx as the transition). Published
        // under the dispute's persisted server-authoritative tenant (SEC-24), so it fans out to that
        // merchant's endpoints only — identically to the unscoped overload above.
        publish(dispute, EventTypes.DISPUTE_EVIDENCE_SUBMITTED, true);
        log.info("Evidence submitted (tenant-scoped): dispute={}, tenant={}", disputeId, tenantId);
        return dispute;
    }

    /**
     * Marks dispute as WON — reverses chargeback reserve.
     */
    @Transactional
    public Dispute win(String disputeId, String actor) {
        Dispute dispute = getOrThrow(disputeId);
        int eventsBefore = dispute.getEvents().size();
        dispute.win(actor);
        dispute = disputeRepository.save(dispute);
        saveNewEvents(dispute, eventsBefore);

        // Post under the dispute's persisted server-authoritative tenant (SEC-24) so
        // the reversal lands on the SAME tenant as the original reserve.
        ledgerPort.reverseChargebackReserve(dispute.getTenantId(), dispute.getId(),
                dispute.getAmount(), dispute.getCurrency());

        // TEST-2: win -> dispute.won (same tx as the transition + the reversal).
        publish(dispute, EventTypes.DISPUTE_WON, true);

        log.info("Dispute won: id={}, amount={} {}", disputeId, dispute.getAmount(), dispute.getCurrency());
        return dispute;
    }

    /**
     * Marks dispute as LOST — finalises chargeback as expense.
     */
    @Transactional
    public Dispute lose(String disputeId, String actor) {
        Dispute dispute = getOrThrow(disputeId);
        int eventsBefore = dispute.getEvents().size();
        dispute.lose(actor);
        dispute = disputeRepository.save(dispute);
        saveNewEvents(dispute, eventsBefore);

        // Post under the dispute's persisted server-authoritative tenant (SEC-24) so
        // the expense lands on the SAME tenant as the original reserve.
        ledgerPort.finaliseChargebackExpense(dispute.getTenantId(), dispute.getId(),
                dispute.getAmount(), dispute.getCurrency());

        // TEST-2: lose -> dispute.lost (same tx as the transition + the expense).
        publish(dispute, EventTypes.DISPUTE_LOST, true);

        log.info("Dispute lost: id={}, amount={} {}", disputeId, dispute.getAmount(), dispute.getCurrency());
        return dispute;
    }

    /**
     * Expires a dispute whose evidence deadline has passed.
     */
    @Transactional
    public Dispute expire(String disputeId) {
        Dispute dispute = getOrThrow(disputeId);
        int eventsBefore = dispute.getEvents().size();
        dispute.expire();
        // expire() is idempotent — it no-ops on an already-terminal dispute and
        // produces no new event. Post to the ledger ONLY when the transition
        // actually happened; otherwise a redundant dispute.expired webhook would
        // double-post the chargeback expense and drain the reserve twice.
        boolean transitioned = dispute.getEvents().size() > eventsBefore;
        dispute = disputeRepository.save(dispute);
        saveNewEvents(dispute, eventsBefore);

        if (transitioned) {
            // Lost by default — finalise chargeback under the dispute's persisted
            // server-authoritative tenant (SEC-24), matching the original reserve.
            ledgerPort.finaliseChargebackExpense(dispute.getTenantId(), dispute.getId(),
                    dispute.getAmount(), dispute.getCurrency());
            // TEST-2: expire / terminal-close -> dispute.closed. Emit ONLY when the transition actually
            // happened (same guard as the ledger finalisation) so a redundant expire produces no
            // duplicate dispute.closed webhook.
            publish(dispute, EventTypes.DISPUTE_CLOSED, true);
            log.info("Dispute expired: id={}", disputeId);
        } else {
            log.info("Dispute {} already terminal, skipping ledger finalisation", disputeId);
        }
        return dispute;
    }

    // -- Query methods --

    public Optional<Dispute> findById(String id) {
        return disputeRepository.findById(id);
    }

    /**
     * SEC-27: tenant-scoped by-id lookup for the REST {@code GET /v1/disputes/{id}} endpoint. Returns
     * the dispute only when it belongs to {@code tenantId}; an absent OR foreign-tenant dispute yields
     * an empty Optional so the controller 404s identically for both (no cross-tenant existence oracle).
     * {@code tenantId} is the authenticated caller's tenant (CallerTenant.require()), never a header.
     */
    public Optional<Dispute> findById(String id, String tenantId) {
        return disputeRepository.findByIdAndTenantId(id, tenantId);
    }

    /**
     * Idempotency lookup used by the webhook handler to distinguish a first
     * delivery from a replay (so it can return {@code created} vs {@code duplicate}).
     */
    public Optional<Dispute> findByTenantIdAndExternalDisputeId(String tenantId, String externalDisputeId) {
        return disputeRepository.findByTenantIdAndExternalDisputeId(tenantId, externalDisputeId);
    }

    public List<Dispute> listByTenant(String tenantId, int limit, int offset) {
        return disputeRepository.findByTenant(tenantId, limit, offset);
    }

    public List<DisputeEvent> getTimeline(String disputeId) {
        return disputeRepository.findEventsByDisputeId(disputeId);
    }

    /**
     * SEC-27: tenant-scoped event timeline for the REST {@code GET /v1/disputes/{id}/events} endpoint.
     * Filters the timeline to {@code tenantId} so a tenant-A caller cannot read a tenant-B dispute's
     * event history by id. A foreign/absent dispute yields an empty list (no oracle).
     */
    public List<DisputeEvent> getTimeline(String disputeId, String tenantId) {
        return disputeRepository.findEventsByDisputeIdAndTenantId(disputeId, tenantId);
    }

    public List<DisputeEvidence> getEvidence(String disputeId) {
        return disputeRepository.findEvidenceByDisputeId(disputeId);
    }

    // -- Helpers --

    /**
     * UNSCOPED fetch-or-throw. Used ONLY by the server-authoritative webhook/internal transitions
     * (win/lose/expire/requestEvidence and the unscoped submitEvidence), where the dispute id is
     * resolved server-side, not from a REST caller. Throws {@link IllegalArgumentException} (not the
     * no-oracle 404) because there is no caller-tenant to scope against on those paths.
     */
    private Dispute getOrThrow(String disputeId) {
        return disputeRepository.findById(disputeId)
                .orElseThrow(() -> new IllegalArgumentException("Dispute not found: " + disputeId));
    }

    /**
     * SEC-27: tenant-scoped fetch-or-404 for REST reads/mutations. Pairs the tenant-scoped finder with
     * {@link TenantOwnership#require} so a tenant-A caller cannot read/mutate a tenant-B dispute by id.
     * Returns 404 (ResourceNotFoundException, via TenantOwnership) on a foreign/absent id — no
     * existence oracle. Mirrors the SEC-26 {@code SubscriptionLifecycleService.getOrThrow} idiom.
     */
    private Dispute getOrThrow(String disputeId, String tenantId) {
        return TenantOwnership.require(
                disputeRepository.findByIdAndTenantId(disputeId, tenantId), "Dispute");
    }

    private void saveNewEvents(Dispute dispute, int eventsBefore) {
        List<DisputeEvent> allEvents = dispute.getEvents();
        for (int i = eventsBefore; i < allEvents.size(); i++) {
            disputeRepository.saveEvent(allEvents.get(i));
        }
    }

    /**
     * TEST-2: writes ONE dispute outbound-event row to the transactional outbox, IN THE CURRENT
     * {@code @Transactional} (so it commits atomically with the state change + the ledger posting — the
     * transactional-outbox guarantee, exactly like the mock synthesizer / HyperSwitch controller). The
     * payload is the dispute {@code data.object}; the event is published under the dispute's persisted
     * server-authoritative TENANT (SEC-24) — never {@code "default"} — so it fans out to the right
     * merchant. {@code livemode} distinguishes a real chargeback (true) from a test-simulated one (false).
     */
    private void publish(Dispute dispute, String internalEventType, boolean livemode) {
        outboxPort.publishEvent(
                EventTypes.AGGREGATE_DISPUTE,
                dispute.getId(),
                internalEventType,
                disputeObject(dispute),
                dispute.getTenantId(),
                livemode);
    }

    /**
     * Builds the dispute {@code data.object} a merchant receives on a {@code dispute.*} webhook. Keys
     * mirror the {@code DisputeController} response shape (snake_case wire names). No card/PAN data is
     * present; the serializer strips card subtrees defensively regardless.
     */
    private static Map<String, Object> disputeObject(Dispute dispute) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("dispute_id", dispute.getId());
        object.put("payment_id", dispute.getPaymentId());
        object.put("amount", dispute.getAmount());
        object.put("currency", dispute.getCurrency());
        object.put("status", dispute.getStatus() != null ? dispute.getStatus().name() : null);
        object.put("reason", dispute.getReasonCode());
        object.put("reason_description", dispute.getReasonDescription());
        object.put("network", dispute.getNetwork());
        object.put("outcome", dispute.getOutcome());
        object.put("external_dispute_id", dispute.getExternalDisputeId());
        object.put("evidence_due_by",
                dispute.getEvidenceDueDate() != null ? dispute.getEvidenceDueDate().toString() : null);
        return object;
    }
}
