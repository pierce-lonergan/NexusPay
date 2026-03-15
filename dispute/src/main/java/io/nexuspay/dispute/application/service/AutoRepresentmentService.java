package io.nexuspay.dispute.application.service;

import io.nexuspay.dispute.application.port.out.DisputeNetworkPort;
import io.nexuspay.dispute.application.port.out.DisputeRepository;
import io.nexuspay.dispute.domain.Dispute;
import io.nexuspay.dispute.domain.DisputeEvidence;
import io.nexuspay.dispute.domain.DisputeReason;
import io.nexuspay.dispute.domain.DisputeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Rule-based auto-representment engine.
 *
 * <p>Evaluates whether a dispute can be automatically represented based on
 * available evidence and dispute characteristics. When confidence is high,
 * evidence is auto-submitted to the card network without manual intervention.</p>
 *
 * <h3>Auto-Submit Rules (Phase 2)</h3>
 * <ul>
 *   <li>Dispute has at least one evidence item matching the reason category</li>
 *   <li>Reason is in the auto-representable set (e.g., NOT_RECEIVED with shipping proof)</li>
 *   <li>Amount is below the configurable auto-submit threshold</li>
 * </ul>
 *
 * <p>Network adapters (Verifi RDR, Ethoca) are stubs in Phase 2; full
 * integration deferred to Phase 3.</p>
 *
 * @since 0.2.4 (Sprint 2.4)
 */
@Service
public class AutoRepresentmentService {

    private static final Logger log = LoggerFactory.getLogger(AutoRepresentmentService.class);

    /**
     * Dispute reasons eligible for auto-representment when sufficient evidence exists.
     */
    private static final Set<DisputeReason> AUTO_REPRESENTABLE_REASONS = Set.of(
            DisputeReason.PRODUCT_NOT_RECEIVED,
            DisputeReason.DUPLICATE_CHARGE,
            DisputeReason.SUBSCRIPTION_CANCELLED
    );

    /**
     * Maximum amount (minor units) eligible for auto-representment.
     * Disputes above this threshold always require manual review.
     */
    private static final long AUTO_SUBMIT_AMOUNT_THRESHOLD = 100_000; // $1,000

    private final DisputeRepository disputeRepository;
    private final DisputeLifecycleService lifecycleService;
    private final List<DisputeNetworkPort> networkAdapters;

    public AutoRepresentmentService(DisputeRepository disputeRepository,
                                     DisputeLifecycleService lifecycleService,
                                     List<DisputeNetworkPort> networkAdapters) {
        this.disputeRepository = disputeRepository;
        this.lifecycleService = lifecycleService;
        this.networkAdapters = networkAdapters;
    }

    /**
     * Evaluates a dispute for auto-representment eligibility.
     *
     * @param disputeId the dispute to evaluate
     * @return {@code true} if evidence was auto-submitted
     */
    public boolean evaluate(String disputeId) {
        Dispute dispute = disputeRepository.findById(disputeId)
                .orElseThrow(() -> new IllegalArgumentException("Dispute not found: " + disputeId));

        if (!isEligibleForAutoRepresentment(dispute)) {
            log.debug("Dispute {} not eligible for auto-representment", disputeId);
            return false;
        }

        List<DisputeEvidence> evidence = disputeRepository.findEvidenceByDisputeId(disputeId);
        if (evidence.isEmpty()) {
            log.debug("Dispute {} has no evidence — skipping auto-submit", disputeId);
            return false;
        }

        if (!hasMatchingEvidence(dispute, evidence)) {
            log.debug("Dispute {} evidence does not match reason category — skipping", disputeId);
            return false;
        }

        // Submit evidence to the card network
        try {
            lifecycleService.submitEvidence(disputeId, "auto-representment");
            log.info("Auto-representment submitted for dispute {}", disputeId);
            return true;
        } catch (Exception e) {
            log.warn("Auto-representment failed for dispute {}: {}", disputeId, e.getMessage());
            return false;
        }
    }

    /**
     * Checks eligibility: correct state, representable reason, amount within threshold.
     */
    private boolean isEligibleForAutoRepresentment(Dispute dispute) {
        // Must be in EVIDENCE_NEEDED or OPENED state
        if (dispute.getStatus() != DisputeState.EVIDENCE_NEEDED
                && dispute.getStatus() != DisputeState.OPENED) {
            return false;
        }

        // Amount must be within threshold
        if (dispute.getAmount() > AUTO_SUBMIT_AMOUNT_THRESHOLD) {
            return false;
        }

        // Reason must be in auto-representable set
        DisputeReason reason = DisputeReason.fromCode(dispute.getReasonCode());
        return AUTO_REPRESENTABLE_REASONS.contains(reason);
    }

    /**
     * Checks whether the available evidence is relevant to the dispute reason.
     */
    private boolean hasMatchingEvidence(Dispute dispute, List<DisputeEvidence> evidence) {
        DisputeReason reason = DisputeReason.fromCode(dispute.getReasonCode());

        return switch (reason) {
            case PRODUCT_NOT_RECEIVED -> evidence.stream()
                    .anyMatch(e -> e.getEvidenceType() == io.nexuspay.dispute.domain.DisputeEvidenceType.SHIPPING_RECEIPT);
            case DUPLICATE_CHARGE -> evidence.stream()
                    .anyMatch(e -> e.getEvidenceType() == io.nexuspay.dispute.domain.DisputeEvidenceType.RECEIPT);
            case SUBSCRIPTION_CANCELLED -> evidence.stream()
                    .anyMatch(e -> e.getEvidenceType() == io.nexuspay.dispute.domain.DisputeEvidenceType.CANCELLATION_POLICY
                            || e.getEvidenceType() == io.nexuspay.dispute.domain.DisputeEvidenceType.SERVICE_AGREEMENT);
            default -> false;
        };
    }
}
