package io.nexuspay.payment.application.service.projection;

import io.nexuspay.payment.application.port.out.PaymentProjectionRepository;
import io.nexuspay.payment.application.port.out.RefundProjectionRepository;
import io.nexuspay.payment.domain.projection.PaymentProjectionRow;
import io.nexuspay.payment.domain.projection.RefundProjectionRow;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * GAP-076 (critique v3 F1): the INNER, TRANSACTIONAL projection writer.
 *
 * <p><b>Why this is a separate bean (the BLOCKER fix).</b> A {@code @Transactional(REQUIRES_NEW)} method's
 * transaction is committed by the Spring proxy AT THE PROXY BOUNDARY — i.e. AFTER the method body returns.
 * A DB error that surfaces only at flush/commit (a NOT NULL / length-overflow constraint, a connection
 * reset, a statement timeout, a unique race) is therefore thrown by the PROXY'S COMMIT, OUTSIDE any
 * try/catch placed INSIDE the method. If the swallow lived in the transactional method it would NOT catch
 * the commit-time failure, and the exception would propagate into the caller (the live charge/refund),
 * breaking the cardinal rule.</p>
 *
 * <p>So the swallow is moved to the OUTER {@link PaymentProjectionService} (which is NOT transactional and
 * therefore surrounds this bean's proxy commit). This bean only does the transactional write; it is allowed
 * to throw — its caller swallows. The {@code REQUIRES_NEW} boundary still guarantees a write failure cannot
 * mark the caller's payment/webhook transaction rollback-only.</p>
 *
 * <p><b>NEVER call these methods directly from the gateway/webhook.</b> Always go through
 * {@link PaymentProjectionService}, whose try/catch covers the proxy commit.</p>
 */
@Component
public class PaymentProjectionTxWriter {

    private final PaymentProjectionRepository payments;
    private final RefundProjectionRepository refunds;

    public PaymentProjectionTxWriter(PaymentProjectionRepository payments,
                                     RefundProjectionRepository refunds) {
        this.payments = payments;
        this.refunds = refunds;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsertPayment(PaymentProjectionRow row) {
        payments.upsert(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsertRefund(RefundProjectionRow row) {
        refunds.upsert(row);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updatePaymentStatus(String paymentId, String tenantId, String status, boolean livemode) {
        payments.updateStatusIfExists(paymentId, tenantId, status, livemode);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateRefundStatus(String refundId, String status) {
        refunds.updateStatusIfExists(refundId, status);
    }
}
