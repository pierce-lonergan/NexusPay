package io.nexuspay.payment.application.service.projection;

import io.nexuspay.payment.application.port.out.PaymentProjectionRepository;
import io.nexuspay.payment.application.port.out.RefundProjectionRepository;
import io.nexuspay.payment.domain.projection.PaymentProjectionRow;
import io.nexuspay.payment.domain.projection.RefundProjectionRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * GAP-076 (critique v3 F1): THE READ SERVICE for the payments/refunds projection — the ONLY surface that
 * reads the read-model. gateway-api delegates to it across the EXISTING {@code :payment-orchestration}
 * edge (no new modulith edge). Read-only.
 *
 * <p><b>Tenant + livemode scoping (non-negotiable).</b> {@code tenantId} is ALWAYS the caller's principal
 * tenant and {@code livemode} ALWAYS the caller key's mode — both pushed to the tenant + livemode-scoped
 * derived finders, so a foreign tenant's rows never materialise (no IDOR, no count leak) and a test key
 * never lists live rows (and vice-versa). There is NO unscoped path.</p>
 *
 * <p><b>Clamping.</b> {@code limit} is clamped to {@code [1,100]} and {@code offset} to {@code >= 0} BEFORE
 * the repo call — a {@code limit <= 0} would otherwise blow up {@code PageRequest.of}, and an over-cap
 * limit is capped at 100 (the over-cap-is-clamped requirement; an improvement over the existing
 * customer/dispute list endpoints which do not clamp).</p>
 *
 * <p><b>Not a source of truth.</b> This read feeds ONLY the GET list endpoints; nothing here moves money,
 * re-drives a capture, or reconciles the ledger.</p>
 */
@Service
public class PaymentProjectionQueryService {

    static final int MAX_LIMIT = 100;
    static final int DEFAULT_LIMIT = 20;

    private final PaymentProjectionRepository payments;
    private final RefundProjectionRepository refunds;

    public PaymentProjectionQueryService(PaymentProjectionRepository payments,
                                         RefundProjectionRepository refunds) {
        this.payments = payments;
        this.refunds = refunds;
    }

    /**
     * Lists the caller tenant's payments (in the caller key's livemode), newest first.
     *
     * @param tenantId       the caller's principal tenant (ALWAYS supplied by the controller)
     * @param livemode       the caller key's mode ({@code principal.live()})
     * @param statusFilter   optional exact status filter (nullable)
     * @param customerFilter optional exact customer_id filter (nullable)
     * @param limit          requested page size (clamped to [1,100])
     * @param offset         requested offset (clamped to >= 0)
     */
    @Transactional(readOnly = true)
    public List<PaymentProjectionRow> listPayments(String tenantId, boolean livemode, String statusFilter,
                                                   String customerFilter, int limit, int offset) {
        int safeLimit = clampLimit(limit);
        int safeOffset = Math.max(offset, 0);
        return payments.listByTenant(tenantId, livemode, statusFilter, customerFilter, safeLimit, safeOffset);
    }

    /**
     * Lists the caller tenant's refunds (in the caller key's livemode), newest first; optionally filtered
     * by parent payment id and/or status.
     */
    @Transactional(readOnly = true)
    public List<RefundProjectionRow> listRefunds(String tenantId, boolean livemode, String paymentFilter,
                                                 String statusFilter, int limit, int offset) {
        int safeLimit = clampLimit(limit);
        int safeOffset = Math.max(offset, 0);
        return refunds.listByTenant(tenantId, livemode, paymentFilter, statusFilter, safeLimit, safeOffset);
    }

    /** Clamp limit to [1, MAX_LIMIT]: limit<=0 -> 1, limit>100 -> 100. */
    private static int clampLimit(int limit) {
        return Math.min(Math.max(limit, 1), MAX_LIMIT);
    }
}
