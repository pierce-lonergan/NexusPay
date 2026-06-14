package io.nexuspay.payment.application.screening;

import io.nexuspay.payment.adapter.out.persistence.ScreeningOriginEntity;
import io.nexuspay.payment.adapter.out.persistence.ScreeningOriginRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Records and recalls the TRUSTED originating screening context for a payment (B-029).
 *
 * <p>At create time the {@link GatedPaymentGateway} resolves the screening mode + tenant from
 * a trusted {@link CallContext}, then persists them here keyed by the gateway payment id. At
 * confirm time the gateway recalls that trusted {@code (tenantId, mode)} — so a tampered intent
 * metadata blob cannot re-classify the rail or fragment the tenant. When no origin row exists
 * (a legacy intent created before B-029, or a create that did not reach this store), confirm
 * falls back to the strictest rail (INTERACTIVE, no tenant).</p>
 */
@Service
public class ScreeningOriginService {

    private static final Logger log = LoggerFactory.getLogger(ScreeningOriginService.class);

    private final ScreeningOriginRepository repository;

    public ScreeningOriginService(ScreeningOriginRepository repository) {
        this.repository = repository;
    }

    /** The trusted originating context recalled at confirm. */
    public record Origin(String tenantId, ScreeningMode mode) {
    }

    /** Idempotent on the gateway payment id: records the trusted {@code (tenantId, mode)}. */
    @Transactional
    public void record(String gatewayPaymentId, CallContext ctx) {
        if (gatewayPaymentId == null || gatewayPaymentId.isBlank() || ctx == null) {
            return;
        }
        if (repository.existsById(gatewayPaymentId)) {
            return; // create is retried (idempotency) — keep the original trusted origin
        }
        try {
            repository.save(new ScreeningOriginEntity(
                    gatewayPaymentId, ctx.tenantId(), ctx.mode().name(), Instant.now()));
        } catch (RuntimeException e) {
            // A failure to persist the origin must not fail an already-authorized payment; confirm
            // will fall back to the strict INTERACTIVE rail (the safe direction) if it is absent.
            log.warn("Failed to record screening origin for payment {} — confirm will fall back to strict rail",
                    gatewayPaymentId, e);
        }
    }

    /**
     * Recalls the trusted origin for a payment. Empty when no row exists (legacy intent) — the
     * caller MUST then fall back to the strictest rail, never to client metadata.
     */
    @Transactional(readOnly = true)
    public Optional<Origin> find(String gatewayPaymentId) {
        if (gatewayPaymentId == null || gatewayPaymentId.isBlank()) {
            return Optional.empty();
        }
        return repository.findById(gatewayPaymentId)
                .map(e -> new Origin(e.getTenantId(), parseMode(e.getScreeningMode())));
    }

    private static ScreeningMode parseMode(String mode) {
        try {
            return ScreeningMode.valueOf(mode);
        } catch (IllegalArgumentException | NullPointerException e) {
            return ScreeningMode.INTERACTIVE; // unknown persisted value → strictest rail
        }
    }
}
