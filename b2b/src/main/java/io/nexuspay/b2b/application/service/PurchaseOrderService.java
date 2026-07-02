package io.nexuspay.b2b.application.service;

import io.nexuspay.b2b.application.port.in.ManagePurchaseOrderUseCase;
import io.nexuspay.b2b.application.port.out.B2bEventPublisher;
import io.nexuspay.b2b.application.port.out.B2bRepository;
import io.nexuspay.b2b.config.B2bProperties;
import io.nexuspay.b2b.domain.PurchaseOrder;
import io.nexuspay.b2b.domain.PurchaseOrderStatus;
import io.nexuspay.common.tenant.TenantOwnership;
import io.nexuspay.iam.application.ApprovalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service for purchase order lifecycle management.
 *
 * <p>GAP-068: PO approval is threshold-gated maker-checker (compared against
 * {@code amount + taxAmount}). GAP-069 decision: PO approval posts NOTHING to the ledger — an
 * approved PO is an executory commitment, not a money movement (this service deliberately has NO
 * ledger dependency; see the b2b {@code LedgerPort} javadoc). The maker-checker gate IS the
 * GAP-068 control here.</p>
 *
 * @since 0.4.2 (Sprint 4.3)
 */
@Service
public class PurchaseOrderService implements ManagePurchaseOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(PurchaseOrderService.class);

    /** GAP-068: the maker-checker action name for purchase-order approvals. */
    public static final String ACTION_PURCHASE_ORDER_APPROVE = "purchase_order_approve";

    private final B2bRepository repository;
    private final B2bEventPublisher eventPublisher;
    private final ApprovalService approvalService;
    private final B2bProperties b2bProperties;

    public PurchaseOrderService(B2bRepository repository, B2bEventPublisher eventPublisher,
                                ApprovalService approvalService, B2bProperties b2bProperties) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.approvalService = approvalService;
        this.b2bProperties = b2bProperties;
    }

    @Override
    @Transactional
    public PurchaseOrderResult createPurchaseOrder(CreatePurchaseOrderCommand command) {
        PurchaseOrder po = PurchaseOrder.create(
                command.tenantId(), command.buyerId(), command.sellerId(),
                command.poNumber(), command.currency(), command.terms());

        po.setTaxAmount(command.taxAmount());
        if (command.lineItems() != null) {
            command.lineItems().forEach(po::addLineItem);
        }
        // GAP-068: stamp the creating principal so the review path can enforce creator != approver.
        if (command.createdBy() != null) {
            po.setCreatedBy(command.createdBy());
        }

        po = repository.savePurchaseOrder(po);

        eventPublisher.publishEvent("PurchaseOrder", po.getId(), "PurchaseOrderCreated",
                Map.of("poNumber", po.getPoNumber(), "buyerId", command.buyerId(),
                        "sellerId", command.sellerId(), "amount", po.getAmount(),
                        "currency", command.currency(), "tenantId", command.tenantId()),
                command.tenantId());

        log.info("PO created: id={}, poNumber={}, amount={}{}", po.getId(), po.getPoNumber(), po.getAmount(), po.getCurrency());
        return toResult(po);
    }

    @Override
    @Transactional(readOnly = true)
    public PurchaseOrderResult getPurchaseOrder(String poId, String tenantId) {
        return toResult(findOrThrow(poId, tenantId));
    }

    @Override
    @Transactional
    public PurchaseOrderResult submitPurchaseOrder(String poId, String tenantId) {
        PurchaseOrder po = findOrThrow(poId, tenantId);
        po.submit();
        po = repository.savePurchaseOrder(po);

        eventPublisher.publishEvent("PurchaseOrder", poId, "PurchaseOrderSubmitted",
                Map.of("poNumber", po.getPoNumber(), "tenantId", tenantId), tenantId);

        log.info("PO submitted: id={}", poId);
        return toResult(po);
    }

    @Override
    @Transactional
    public ApproveOutcome approvePurchaseOrder(String poId, String tenantId, String requestedBy) {
        // SEC-23: tenant-scoped load BEFORE any state logic. 404 on absent OR wrong-tenant.
        PurchaseOrder po = findOrThrow(poId, tenantId);
        // State defense up front: only a SUBMITTED PO is approvable — fail loudly on BOTH the
        // threshold and the single-step path (never create an approval for a stale PO).
        if (po.getStatus() != PurchaseOrderStatus.SUBMITTED) {
            throw new IllegalStateException("Can only approve SUBMITTED purchase orders");
        }

        long total = po.getAmount() + po.getTaxAmount();
        if (total >= b2bProperties.getApprovalThreshold()) {
            // WAVE1 review fix — IDEMPOTENT RE-REQUEST: the PO deliberately stays SUBMITTED while its
            // approval is pending, so a retried approve call must return the EXISTING approval's id
            // instead of stacking a duplicate PENDING row (duplicates become permanently-stuck poison
            // rows once one executes or the PO is cancelled). Same 202 shape, no duplicate event.
            var existing = approvalService.findPendingByActionAndResource(
                    ACTION_PURCHASE_ORDER_APPROVE, poId, tenantId);
            if (existing.isPresent()) {
                log.info("PO approval already pending — returning it idempotently: id={}, approval={}",
                        poId, existing.get().getId());
                return new ApproveOutcome(toResult(po), existing.get().getId());
            }

            // GAP-068 maker-checker: at/above threshold a SECOND principal must review; the PO
            // stays SUBMITTED. No ledger posting on either path (PO-commitment decision).
            var payload = new LinkedHashMap<String, Object>();
            payload.put("po_id", poId);
            payload.put("amount", total);
            payload.put("currency", po.getCurrency());
            if (po.getCreatedBy() != null) {
                payload.put("created_by", po.getCreatedBy());
            }
            var approval = approvalService.createApproval(
                    ACTION_PURCHASE_ORDER_APPROVE, "PurchaseOrder", poId,
                    payload, requestedBy, tenantId);

            eventPublisher.publishEvent("PurchaseOrder", poId, "PurchaseOrderApprovalRequested",
                    Map.of("approvalId", approval.getId(), "amount", total,
                            "requestedBy", requestedBy, "tenantId", tenantId),
                    tenantId);

            log.info("PO approval requested (>= threshold {}): id={}, approval={}, requestedBy={}",
                    b2bProperties.getApprovalThreshold(), poId, approval.getId(), requestedBy);
            return new ApproveOutcome(toResult(po), approval.getId());
        }

        // Below threshold: the current single-step approve stands.
        return new ApproveOutcome(executeApproved(poId, tenantId), null);
    }

    /**
     * GAP-069 decision: NO journal entry at PO approval — an approved PO is an executory commitment
     * (neither party has performed; no asset/liability exists to recognize). First GL recognition
     * happens at invoice payment / vendor-payment approval. See the b2b {@code LedgerPort} javadoc.
     */
    @Override
    @Transactional
    public PurchaseOrderResult executeApproved(String poId, String tenantId) {
        PurchaseOrder po = findOrThrow(poId, tenantId);
        po.approve(); // SUBMITTED-only guard — a replayed execution throws
        po = repository.savePurchaseOrder(po);

        eventPublisher.publishEvent("PurchaseOrder", poId, "PurchaseOrderApproved",
                Map.of("poNumber", po.getPoNumber(), "dueDate", po.getDueDate().toString(),
                        "tenantId", tenantId), tenantId);

        log.info("PO approved: id={}, dueDate={}", poId, po.getDueDate());
        return toResult(po);
    }

    @Override
    @Transactional
    public void cancelPurchaseOrder(String poId, String tenantId) {
        PurchaseOrder po = findOrThrow(poId, tenantId);
        po.cancel();
        repository.savePurchaseOrder(po);

        eventPublisher.publishEvent("PurchaseOrder", poId, "PurchaseOrderCancelled",
                Map.of("tenantId", tenantId), tenantId);

        log.info("PO cancelled: id={}", poId);
    }

    private PurchaseOrder findOrThrow(String poId, String tenantId) {
        // SEC-23: tenant-scoped finder + 404 on absent OR wrong-tenant (no existence oracle).
        return TenantOwnership.require(
                repository.findPurchaseOrderById(poId, tenantId), "Purchase order");
    }

    private PurchaseOrderResult toResult(PurchaseOrder po) {
        return new PurchaseOrderResult(
                po.getId(), po.getPoNumber(), po.getBuyerId(), po.getSellerId(),
                po.getAmount(), po.getTaxAmount(), po.getCurrency(), po.getStatus(),
                po.getTerms(), po.getDueDate(), po.getLineItems(), po.getCreatedAt());
    }
}
