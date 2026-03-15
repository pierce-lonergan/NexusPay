package io.nexuspay.workflow.application;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Durable payment workflow with retry and webhook-based confirmation.
 *
 * <p>This workflow orchestrates the payment lifecycle:</p>
 * <ol>
 *     <li>Create payment in HyperSwitch</li>
 *     <li>Wait for webhook confirmation (or timeout)</li>
 *     <li>On success → publish payment event to outbox</li>
 *     <li>On failure → retry with backoff (up to 3 attempts)</li>
 *     <li>On timeout → mark as failed</li>
 * </ol>
 *
 * <p>The workflow is durable: if the NexusPay process crashes between steps,
 * Temporal automatically resumes from the last completed step.</p>
 *
 * @since 0.2.0 (Sprint 2.2)
 */
@WorkflowInterface
public interface PaymentWithRetryWorkflow {

    /**
     * Processes a payment through creation, confirmation, and event publication.
     *
     * @param request the payment request
     * @return the payment result
     */
    @WorkflowMethod
    PaymentWorkflowResult processPayment(PaymentWorkflowRequest request);

    /**
     * Signal: webhook confirmation received from HyperSwitch.
     *
     * @param paymentId the confirmed payment ID
     * @param status the payment status (e.g., "succeeded", "failed")
     */
    @SignalMethod
    void onPaymentConfirmed(String paymentId, String status);

    /**
     * Signal: cancel this payment workflow.
     */
    @SignalMethod
    void cancelPayment();

    /**
     * Query: get the current status of this payment workflow.
     *
     * @return current workflow status
     */
    @QueryMethod
    String getStatus();
}
