package io.nexuspay.payment.application.screening;

import io.nexuspay.payment.adapter.out.persistence.CaptureHoldEntity;
import io.nexuspay.payment.adapter.out.persistence.CaptureHoldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Records and enforces capture holds for fraud-REVIEW payments (B-024/B-027). A held
 * payment is authorized but must not be captured until an authorized back-office action
 * releases it. {@code GatedPaymentGateway} writes a hold on REVIEW and refuses capture
 * while held.
 */
@Service
public class CaptureHoldService {

    static final String HELD = "HELD";
    static final String RELEASED = "RELEASED";

    private static final Logger log = LoggerFactory.getLogger(CaptureHoldService.class);

    private final CaptureHoldRepository repository;

    public CaptureHoldService(CaptureHoldRepository repository) {
        this.repository = repository;
    }

    /** Idempotent on payment id: places a HELD hold linking the payment to its fraud assessment. */
    @Transactional
    public void hold(String paymentId, String tenantId, UUID fraudAssessmentId) {
        if (paymentId == null || paymentId.isBlank()) {
            return;
        }
        if (tenantId == null || tenantId.isBlank()) {
            // tenant_id is NOT NULL; rather than throw (the payment already authorized with
            // manual capture, so funds are not captured), skip the DB hold-row and warn.
            log.warn("Capture-hold for {} skipped: no tenant resolved (payment stays manual-capture at the PSP)", paymentId);
            return;
        }
        if (repository.existsById(paymentId)) {
            return; // already held/released — do not clobber an analyst's release
        }
        repository.save(new CaptureHoldEntity(
                paymentId,
                tenantId,
                fraudAssessmentId != null ? fraudAssessmentId.toString() : null,
                HELD,
                Instant.now()));
        log.info("Capture HELD for payment {} (tenant {}, assessment {})",
                paymentId, tenantId, fraudAssessmentId);
    }

    /** True when a hold exists and is still HELD (not yet released). */
    @Transactional(readOnly = true)
    public boolean isHeld(String paymentId) {
        if (paymentId == null) {
            return false;
        }
        return repository.findById(paymentId)
                .map(e -> HELD.equals(e.getStatus()))
                .orElse(false);
    }

    /** Releases a hold so capture can proceed (authorized back-office action). */
    @Transactional
    public void release(String paymentId, String releasedBy) {
        repository.findById(paymentId).ifPresent(e -> {
            e.setStatus(RELEASED);
            e.setReleasedBy(releasedBy);
            e.setReleasedAt(Instant.now());
            repository.save(e);
            log.info("Capture hold RELEASED for payment {} by {}", paymentId, releasedBy);
        });
    }
}
