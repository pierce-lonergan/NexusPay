package io.nexuspay.workflow.application;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;

/**
 * Implementation of the durable payment workflow.
 *
 * <p>This workflow is deterministic — it must produce the same results when
 * replayed from Temporal's event history. Therefore:</p>
 * <ul>
 *     <li>No direct I/O (use activities instead)</li>
 *     <li>No Thread.sleep (use Workflow.sleep instead)</li>
 *     <li>No non-deterministic calls (use Workflow.currentTimeMillis instead of System.currentTimeMillis)</li>
 *     <li>No mutable shared state</li>
 * </ul>
 *
 * @since 0.2.0 (Sprint 2.2)
 */
public class PaymentWithRetryWorkflowImpl implements PaymentWithRetryWorkflow {

    private static final Logger log = Workflow.getLogger(PaymentWithRetryWorkflowImpl.class);

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration CONFIRMATION_TIMEOUT = Duration.ofMinutes(5);

    private final PaymentActivities activities = Workflow.newActivityStub(
            PaymentActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setMaximumAttempts(3)
                            .setBackoffCoefficient(2.0)
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setMaximumInterval(Duration.ofSeconds(30))
                            .build())
                    .build());

    // Workflow state (deterministic — updated only by workflow code and signals)
    private String status = "INITIALIZED";
    private boolean confirmed = false;
    private boolean cancelled = false;
    private String confirmedStatus = null;

    @Override
    public PaymentWorkflowResult processPayment(PaymentWorkflowRequest request) {
        int attemptCount = 0;

        while (attemptCount < MAX_ATTEMPTS && !cancelled) {
            attemptCount++;
            status = "ATTEMPT_" + attemptCount;

            try {
                // Step 1: Create payment in HyperSwitch
                log.info("Creating payment: paymentId={}, attempt={}", request.paymentId(), attemptCount);
                String externalPaymentId = activities.createPayment(request);
                status = "AWAITING_CONFIRMATION";

                // Step 2: Wait for webhook confirmation (signal) or timeout
                log.info("Waiting for confirmation: paymentId={}, externalId={}",
                        request.paymentId(), externalPaymentId);

                boolean received = Workflow.await(CONFIRMATION_TIMEOUT, () -> confirmed || cancelled);

                if (cancelled) {
                    log.info("Payment cancelled: paymentId={}", request.paymentId());
                    activities.voidPayment(externalPaymentId);
                    status = "CANCELLED";
                    return PaymentWorkflowResult.cancelled(request.paymentId(), attemptCount);
                }

                if (!received) {
                    // Timeout — retry if attempts remain
                    log.warn("Confirmation timeout: paymentId={}, attempt={}",
                            request.paymentId(), attemptCount);
                    confirmed = false;
                    continue;
                }

                // Step 3: Confirmation received — check status
                if ("succeeded".equals(confirmedStatus)) {
                    // Step 4: Publish payment event
                    activities.publishPaymentEvent(
                            request.paymentId(), externalPaymentId, "CAPTURED", request.tenantId());
                    status = "SUCCEEDED";
                    log.info("Payment succeeded: paymentId={}, externalId={}",
                            request.paymentId(), externalPaymentId);
                    return PaymentWorkflowResult.success(request.paymentId(), externalPaymentId, attemptCount);
                } else {
                    // Payment failed at PSP — retry if attempts remain
                    log.warn("Payment failed at PSP: paymentId={}, status={}, attempt={}",
                            request.paymentId(), confirmedStatus, attemptCount);
                    confirmed = false;
                    confirmedStatus = null;
                }

            } catch (Exception e) {
                log.error("Payment attempt failed: paymentId={}, attempt={}, error={}",
                        request.paymentId(), attemptCount, e.getMessage());
                if (attemptCount >= MAX_ATTEMPTS) {
                    status = "FAILED";
                    return PaymentWorkflowResult.failed(request.paymentId(), attemptCount, e.getMessage());
                }
                // Backoff before retry
                Workflow.sleep(Duration.ofSeconds((long) Math.pow(2, attemptCount)));
            }
        }

        status = cancelled ? "CANCELLED" : "FAILED";
        return cancelled
                ? PaymentWorkflowResult.cancelled(request.paymentId(), attemptCount)
                : PaymentWorkflowResult.timedOut(request.paymentId(), attemptCount);
    }

    @Override
    public void onPaymentConfirmed(String paymentId, String status) {
        log.info("Payment confirmation signal received: paymentId={}, status={}", paymentId, status);
        this.confirmedStatus = status;
        this.confirmed = true;
    }

    @Override
    public void cancelPayment() {
        log.info("Payment cancellation signal received");
        this.cancelled = true;
    }

    @Override
    public String getStatus() {
        return status;
    }
}
