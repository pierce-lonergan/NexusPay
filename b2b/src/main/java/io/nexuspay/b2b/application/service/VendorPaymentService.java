package io.nexuspay.b2b.application.service;

import io.nexuspay.b2b.application.port.in.ManageVendorPaymentUseCase;
import io.nexuspay.b2b.application.port.out.B2bEventPublisher;
import io.nexuspay.b2b.application.port.out.B2bRepository;
import io.nexuspay.b2b.application.port.out.LedgerPort;
import io.nexuspay.b2b.application.port.out.VendorPaymentExecutionPort;
import io.nexuspay.b2b.config.B2bProperties;
import io.nexuspay.b2b.domain.VendorPayment;
import io.nexuspay.b2b.domain.VendorPaymentStatus;
import io.nexuspay.common.tenant.CallerMode;
import io.nexuspay.common.tenant.TenantOwnership;
import io.nexuspay.iam.application.ApprovalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for vendor payment creation, approval, batching, and execution.
 *
 * <p>GAP-068: approval is threshold-gated maker-checker. Below
 * {@code nexuspay.b2b.approval-threshold} the approve call executes single-step; at/above it, a
 * PENDING approval is created in the iam maker-checker machinery and a DIFFERENT principal must
 * review it via {@code B2bApprovalService}.</p>
 *
 * <p>GAP-069 (CARDINAL RULE): {@link #executeApproved} books the accrual + disbursement journal
 * entries INSIDE the same transaction as the state transitions — no try/catch, no @Async, no
 * REQUIRES_NEW. A posting failure rolls the whole approval/disbursement back.</p>
 *
 * @since 0.4.2 (Sprint 4.3)
 */
@Service
public class VendorPaymentService implements ManageVendorPaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(VendorPaymentService.class);

    /** GAP-068: the maker-checker action name for vendor-payment approvals. */
    public static final String ACTION_VENDOR_PAYMENT_APPROVE = "vendor_payment_approve";

    private final B2bRepository repository;
    private final VendorPaymentExecutionPort executionPort;
    private final B2bEventPublisher eventPublisher;
    private final LedgerPort ledgerPort;
    private final ApprovalService approvalService;
    private final B2bProperties b2bProperties;

    public VendorPaymentService(B2bRepository repository,
                                 VendorPaymentExecutionPort executionPort,
                                 B2bEventPublisher eventPublisher,
                                 LedgerPort ledgerPort,
                                 ApprovalService approvalService,
                                 B2bProperties b2bProperties) {
        this.repository = repository;
        this.executionPort = executionPort;
        this.eventPublisher = eventPublisher;
        this.ledgerPort = ledgerPort;
        this.approvalService = approvalService;
        this.b2bProperties = b2bProperties;
    }

    @Override
    @Transactional
    public VendorPaymentResult createVendorPayment(CreateVendorPaymentCommand command) {
        VendorPayment payment = VendorPayment.create(
                command.tenantId(), command.vendorId(), command.amount(),
                command.currency(), command.method());

        if (command.remittanceInfo() != null) {
            payment.setRemittanceInfo(command.remittanceInfo());
        }
        if (command.scheduledAt() != null) {
            payment.setScheduledAt(command.scheduledAt());
        }
        // GAP-068: stamp the creating principal so the review path can enforce creator != approver.
        if (command.createdBy() != null) {
            payment.setCreatedBy(command.createdBy());
        }

        payment = repository.saveVendorPayment(payment);

        eventPublisher.publishEvent("VendorPayment", payment.getId(), "VendorPaymentCreated",
                Map.of("vendorId", command.vendorId(), "amount", command.amount(),
                        "currency", command.currency(), "method", command.method().name(),
                        "tenantId", command.tenantId()),
                command.tenantId());

        log.info("Vendor payment created: id={}, vendor={}, amount={}{}", payment.getId(),
                command.vendorId(), command.amount(), command.currency());

        return toResult(payment);
    }

    @Override
    @Transactional
    public ApproveOutcome approveVendorPayment(String paymentId, String tenantId, String requestedBy) {
        // SEC-BATCH-1: tenant-scoped load BEFORE any state logic — money-moving approval can never be
        // triggered across tenants. 404 on absent OR wrong-tenant.
        VendorPayment payment = findOrThrow(paymentId, tenantId);
        // Replay/state defense up front: only a PENDING payment is approvable — a non-PENDING payment
        // must fail loudly on BOTH the threshold and the single-step path (never create a stale approval).
        if (payment.getStatus() != VendorPaymentStatus.PENDING) {
            throw new IllegalStateException("Can only approve PENDING vendor payments");
        }

        if (payment.getAmount() >= b2bProperties.getApprovalThreshold()) {
            // WAVE1 review fix — IDEMPOTENT RE-REQUEST: the payment deliberately stays PENDING while
            // its approval is pending, so the state guard above cannot stop a retried/double-clicked
            // approve call. Without this check every repeat POST minted ANOTHER pending_approvals row
            // for the same payment; once one executed, the leftovers became permanently-stuck poison
            // rows (each review attempt claims, fails the domain guard, rolls back to PENDING).
            // Return the EXISTING approval's id in the same 202 shape instead — no duplicate row, no
            // duplicate ApprovalRequested event.
            var existing = approvalService.findPendingByActionAndResource(
                    ACTION_VENDOR_PAYMENT_APPROVE, paymentId, tenantId);
            if (existing.isPresent()) {
                log.info("Vendor payment approval already pending — returning it idempotently: "
                        + "id={}, approval={}", paymentId, existing.get().getId());
                return new ApproveOutcome(toResult(payment), existing.get().getId());
            }

            // GAP-068 maker-checker: at/above threshold a SECOND principal must review. The payment
            // stays PENDING; no ledger posting, no execution — nothing money-moving happens here.
            var payload = new LinkedHashMap<String, Object>();
            payload.put("payment_id", paymentId);
            payload.put("amount", payment.getAmount());
            payload.put("currency", payment.getCurrency());
            payload.put("method", payment.getMethod().name());
            if (payment.getCreatedBy() != null) {
                payload.put("created_by", payment.getCreatedBy());
            }
            var approval = approvalService.createApproval(
                    ACTION_VENDOR_PAYMENT_APPROVE, "VendorPayment", paymentId,
                    payload, requestedBy, tenantId);

            eventPublisher.publishEvent("VendorPayment", paymentId, "VendorPaymentApprovalRequested",
                    Map.of("approvalId", approval.getId(), "amount", payment.getAmount(),
                            "requestedBy", requestedBy, "tenantId", tenantId),
                    tenantId);

            log.info("Vendor payment approval requested (>= threshold {}): id={}, approval={}, requestedBy={}",
                    b2bProperties.getApprovalThreshold(), paymentId, approval.getId(), requestedBy);
            return new ApproveOutcome(toResult(payment), approval.getId());
        }

        // Below threshold: the single-step approve stands (and now also executes + books postings).
        return new ApproveOutcome(executeApproved(paymentId, tenantId), null);
    }

    /**
     * GAP-069: the approved-execution pipeline, in ONE transaction — approve() (PENDING-only state
     * guard = replay defense), the ACCRUAL entry, the stub disbursement, markPaid(externalReference),
     * and the DISBURSEMENT entry (keyed off the CONFIRMED stub result, never off intent). Any failure
     * rolls the whole pipeline back: no state advance without its balanced ledger entries, and no
     * ledger residue without the state advance. The vendor rail is the in-process stub (GAP-067 tracks
     * a real rail), which is what makes the one-transaction design possible — unlike the refund flow's
     * external PSP call.
     */
    @Override
    @Transactional
    public VendorPaymentResult executeApproved(String paymentId, String tenantId) {
        VendorPayment payment = findOrThrow(paymentId, tenantId);
        payment.approve(); // PENDING-only guard — a replayed execution throws BEFORE any posting

        // WAVE1 review fix: record the caller's key mode in entry metadata (marketplace-edge
        // mirror) — this runs on the request thread (direct approve or the b2b review path).
        boolean livemode = CallerMode.isLive();

        // Accrual: DR vendor expense / CR vendor payable — atomic with the approval (NO try/catch).
        ledgerPort.postVendorPaymentApproved(tenantId, paymentId, payment.getAmount(),
                payment.getCurrency(), livemode);

        var result = executionPort.execute(new VendorPaymentExecutionPort.ExecutionRequest(
                paymentId, payment.getVendorId(), payment.getAmount(),
                payment.getCurrency(), payment.getMethod(), payment.getRemittanceInfo()));
        if (!result.success()) {
            // The stub is deterministic-success; a real-rail failure/retry design is deferred to
            // GAP-067. Failing the tx here keeps the invariant honest: no APPROVED state (and no
            // accrual entry) survives an unconfirmed disbursement.
            throw new IllegalStateException(
                    "Vendor payment execution failed: " + result.failureReason());
        }

        payment.markPaid(result.externalReference());
        // Settlement: DR vendor payable / CR cash clearing — keyed off the CONFIRMED stub result.
        ledgerPort.postVendorPaymentDisbursed(tenantId, paymentId, result.externalReference(),
                payment.getAmount(), payment.getCurrency(), livemode);

        payment = repository.saveVendorPayment(payment);

        eventPublisher.publishEvent("VendorPayment", paymentId, "VendorPaymentApproved",
                Map.of("tenantId", tenantId), tenantId);
        eventPublisher.publishEvent("VendorPayment", paymentId, "VendorPaymentPaid",
                Map.of("externalReference", result.externalReference(),
                        "amount", payment.getAmount(), "tenantId", tenantId),
                tenantId);

        log.info("Vendor payment approved+disbursed: id={}, ref={}", paymentId, result.externalReference());
        return toResult(payment);
    }

    @Override
    @Transactional
    public List<VendorPaymentResult> createBatch(List<CreateVendorPaymentCommand> commands, String tenantId) {
        String batchId = "batch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        List<VendorPaymentResult> results = new ArrayList<>();

        for (CreateVendorPaymentCommand command : commands) {
            VendorPayment payment = VendorPayment.create(
                    command.tenantId(), command.vendorId(), command.amount(),
                    command.currency(), command.method());
            payment.assignToBatch(batchId);
            if (command.remittanceInfo() != null) {
                payment.setRemittanceInfo(command.remittanceInfo());
            }
            // GAP-068: stamp the creating principal (creator != approver at review).
            if (command.createdBy() != null) {
                payment.setCreatedBy(command.createdBy());
            }

            payment = repository.saveVendorPayment(payment);
            results.add(toResult(payment));
        }

        eventPublisher.publishEvent("VendorPayment", batchId, "VendorPaymentBatchCreated",
                Map.of("batchId", batchId, "count", commands.size(), "tenantId", tenantId),
                tenantId);

        log.info("Vendor payment batch created: batchId={}, count={}", batchId, commands.size());
        return results;
    }

    @Override
    @Transactional(readOnly = true)
    public VendorPaymentResult getVendorPayment(String paymentId, String tenantId) {
        // SEC-BATCH-1: tenant-scoped by-id read.
        return toResult(findOrThrow(paymentId, tenantId));
    }

    private VendorPayment findOrThrow(String paymentId, String tenantId) {
        // SEC-BATCH-1: tenant-scoped finder + 404 on absent OR wrong-tenant (no existence oracle).
        return TenantOwnership.require(
                repository.findVendorPaymentById(paymentId, tenantId), "Vendor payment");
    }

    private VendorPaymentResult toResult(VendorPayment vp) {
        return new VendorPaymentResult(
                vp.getId(), vp.getVendorId(), vp.getAmount(), vp.getCurrency(),
                vp.getMethod(), vp.getStatus(), vp.getBatchId(), vp.getRemittanceInfo(),
                vp.getExternalReference(), vp.getScheduledAt(), vp.getPaidAt(), vp.getCreatedAt());
    }
}
