package io.nexuspay.payment.application.service.projection;

import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.RefundResponse;
import io.nexuspay.payment.domain.projection.PaymentProjectionRow;
import io.nexuspay.payment.domain.projection.RefundProjectionRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * GAP-076 (critique v3 F1): THE BEST-EFFORT WRITER for the payments/refunds READ-MODEL projection.
 *
 * <p>★★★ CARDINAL RULE (money-safety). ★★★ Every method here is BEST-EFFORT and READ-ONLY-feeding: it
 * builds a projection row and upserts it idempotently by PK, wrapped in a try/catch that SWALLOWS any
 * failure (logged, never rethrown) — EXACTLY like {@code WebhookMetadataService.record}. A projection
 * write can NEVER fail, block, slow, or roll back a payment/refund op. The methods return {@code void}
 * and declare NO {@code throws}. The projection is NEVER read here to make a money decision — the gateway
 * + webhook only WRITE; only {@code PaymentProjectionQueryService} (the two GET list endpoints) reads.</p>
 *
 * <p><b>The swallow surrounds the COMMIT (the BLOCKER fix).</b> This service is DELIBERATELY NOT
 * {@code @Transactional}. The actual DB write runs in {@link PaymentProjectionTxWriter}, whose methods are
 * {@code @Transactional(REQUIRES_NEW)}. With Spring's proxy, that inner transaction is COMMITTED at the
 * PROXY BOUNDARY — i.e. when {@code PaymentProjectionTxWriter.upsert*(...)} RETURNS to THIS class. Because
 * the try/catch here surrounds that call, it catches a failure that surfaces only at the flush/commit (a
 * NOT NULL / length-overflow / connection-reset / unique-race that Hibernate defers to commit), not just a
 * synchronous in-body throw. Previously the swallow lived INSIDE the {@code REQUIRES_NEW} method, so the
 * proxy commit threw OUTSIDE it and propagated into the live charge — a phantom failure. The
 * {@code REQUIRES_NEW} boundary additionally guarantees the projection write cannot mark the caller's
 * payment/webhook transaction rollback-only.</p>
 *
 * <p><b>Two extra fail-safes layered on the cardinal rule.</b> (1) On a null/blank {@code tenantId} the
 * upsert is SKIPPED entirely — {@code payments.tenant_id} is NOT NULL, so a null-tenant write would throw
 * at commit, and a null-tenant row is unqueryable anyway (every read is tenant-scoped). The live-create
 * path can legitimately have no trusted tenant. (2) {@code error_code} / {@code error_message} /
 * {@code reason} are truncated to their column caps before the upsert so an over-length PSP message cannot
 * overflow {@code VARCHAR(128)} / {@code VARCHAR(512)} / {@code VARCHAR(255)} at commit. Even if a
 * commit-time failure slips past both, the surrounding swallow still keeps the payment op safe.</p>
 *
 * <p><b>Idempotency / no-regression.</b> The upsert is keyed by the payment/refund id PK (no duplicate
 * rows on a create-retry or a sync+webhook race), {@code created_at} is set once from the first write,
 * and a write-time monotonic status-precedence guard (in the repository adapter) prevents an out-of-order
 * write from regressing a terminal status.</p>
 */
@Service
public class PaymentProjectionService {

    private static final Logger log = LoggerFactory.getLogger(PaymentProjectionService.class);

    // Column caps (V4041) — values are truncated to these before the upsert so an over-length PSP string
    // can never overflow the VARCHAR at commit (which would throw OUTSIDE the inner tx body).
    private static final int ERROR_CODE_MAX = 128;
    private static final int ERROR_MESSAGE_MAX = 512;
    private static final int REASON_MAX = 255;

    private final PaymentProjectionTxWriter writer;

    public PaymentProjectionService(PaymentProjectionTxWriter writer) {
        this.writer = writer;
    }

    /**
     * Best-effort upsert of a payment's current state into the read-model. Stamps the SERVER-DERIVED
     * {@code tenantId} + {@code livemode}. No-op on a null/blank id OR a null/blank tenantId (a null-tenant
     * row is unqueryable + would violate the NOT NULL at commit). NEVER throws — the try/catch surrounds the
     * inner {@code REQUIRES_NEW} commit.
     */
    public void record(PaymentResponse response, String tenantId, boolean livemode) {
        if (response == null || response.gatewayPaymentId() == null
                || response.gatewayPaymentId().isBlank()) {
            return;
        }
        if (tenantId == null || tenantId.isBlank()) {
            // A null-tenant projection row is unqueryable (every read is tenant-scoped) AND would throw the
            // NOT NULL constraint at commit — skip it. The {id} endpoint still serves the payment from the PSP.
            log.debug("payment projection skipped for {} — no trusted tenant (row would be unqueryable)",
                    response.gatewayPaymentId());
            return;
        }
        try {
            writer.upsertPayment(new PaymentProjectionRow(
                    response.gatewayPaymentId(), tenantId, livemode, response.status(),
                    response.amount(), response.currency(), response.captureMethod(),
                    response.customerId(), response.connectorName(),
                    truncate(response.errorCode(), ERROR_CODE_MAX),
                    truncate(response.errorMessage(), ERROR_MESSAGE_MAX),
                    response.createdAt() != null ? response.createdAt() : Instant.now(),
                    Instant.now()));
        } catch (RuntimeException e) {
            // A projection write failure must NEVER fail the payment op (cardinal rule). This catch
            // SURROUNDS the inner REQUIRES_NEW proxy commit, so a flush/commit-time DataIntegrity/Unexpected
            // RollbackException is swallowed too, not just a synchronous in-body throw. Log + swallow.
            log.warn("payment projection upsert failed for {} — list read-model may lag; payment op proceeds",
                    response.gatewayPaymentId(), e);
        }
    }

    /**
     * Best-effort upsert of a refund's current state into the read-model. Stamps tenant + livemode.
     * No-op on a null/blank id OR a null/blank tenantId. NEVER throws.
     */
    public void recordRefund(RefundResponse response, String tenantId, boolean livemode) {
        if (response == null || response.gatewayRefundId() == null
                || response.gatewayRefundId().isBlank()) {
            return;
        }
        if (tenantId == null || tenantId.isBlank()) {
            log.debug("refund projection skipped for {} — no trusted tenant (row would be unqueryable)",
                    response.gatewayRefundId());
            return;
        }
        try {
            writer.upsertRefund(new RefundProjectionRow(
                    response.gatewayRefundId(), response.paymentId(), tenantId, livemode,
                    response.status(), response.amount(), response.currency(),
                    truncate(response.reason(), REASON_MAX),
                    response.connectorName(),
                    truncate(response.errorCode(), ERROR_CODE_MAX),
                    truncate(response.errorMessage(), ERROR_MESSAGE_MAX),
                    response.createdAt() != null ? response.createdAt() : Instant.now(),
                    Instant.now()));
        } catch (RuntimeException e) {
            log.warn("refund projection upsert failed for {} — list read-model may lag; refund op proceeds",
                    response.gatewayRefundId(), e);
        }
    }

    /**
     * GAP-076 async-live settlement hook (called from {@code HyperSwitchWebhookController}). Best-effort
     * advances a PROJECTED payment's status (e.g. processing -&gt; succeeded/failed/cancelled) when the
     * HyperSwitch webhook lands — the only place a LIVE payment transitions without a gateway call.
     *
     * <p>Update-if-exists: when no projection row exists for the id (a payment born before the read-model
     * shipped, the forward-fill gap), this is a no-op — the {@code GET /{id}} endpoint still serves it from
     * HyperSwitch. When a row DOES exist, the repository's precedence guard advances the status
     * monotonically (a late {@code processing} cannot regress a {@code succeeded}). NEVER throws.</p>
     *
     * @param status the target projection status (a {@code PaymentResponse.STATUS_*} value)
     */
    public void recordStatusUpdate(String paymentId, String tenantId, String status, boolean livemode) {
        if (paymentId == null || paymentId.isBlank() || status == null || status.isBlank()) {
            return;
        }
        try {
            // Update-if-exists (precedence-guarded). A missing row is intentionally NOT back-filled here
            // (forward-fill: birth happens at the gateway create hook, not at the webhook). The {id}
            // endpoint still serves a pre-read-model payment from HyperSwitch.
            writer.updatePaymentStatus(paymentId, tenantId, status, livemode);
        } catch (RuntimeException e) {
            log.warn("payment projection status update failed for {} — list read-model may lag", paymentId, e);
        }
    }

    /**
     * GAP-076 async-live settlement hook for refunds (refund_succeeded / refund_failed). Best-effort
     * advances a PROJECTED refund's status. Update-if-exists, precedence-guarded, NEVER throws.
     *
     * @param status the target refund status (a {@code RefundResponse.STATUS_*} value)
     */
    public void recordRefundStatusUpdate(String refundId, String paymentId, String tenantId,
                                         String status, boolean livemode) {
        if (refundId == null || refundId.isBlank() || status == null || status.isBlank()) {
            return;
        }
        try {
            writer.updateRefundStatus(refundId, status);
        } catch (RuntimeException e) {
            log.warn("refund projection status update failed for {} — list read-model may lag", refundId, e);
        }
    }

    /** Truncate a nullable value to a column cap so it cannot overflow the VARCHAR at commit. */
    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
