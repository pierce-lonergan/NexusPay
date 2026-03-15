package io.nexuspay.workflow.application;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Activity interface for payment-related operations.
 *
 * <p>Activities are the building blocks of workflows — they represent
 * individual steps that can fail and be retried independently. Each activity
 * call is recorded in Temporal's event history, enabling replay after crashes.</p>
 *
 * <p>Implementations are provided by the payment-orchestration module and
 * registered with the Temporal worker at startup.</p>
 *
 * @since 0.2.0 (Sprint 2.2)
 */
@ActivityInterface
public interface PaymentActivities {

    /**
     * Creates a payment in HyperSwitch.
     *
     * @param request the payment request
     * @return the external payment ID from HyperSwitch
     */
    @ActivityMethod
    String createPayment(PaymentWorkflowRequest request);

    /**
     * Confirms a payment in HyperSwitch (for manual capture flows).
     *
     * @param externalPaymentId the HyperSwitch payment ID
     * @return the confirmed status
     */
    @ActivityMethod
    String confirmPayment(String externalPaymentId);

    /**
     * Publishes a payment event to the transactional outbox.
     *
     * @param paymentId the NexusPay payment ID
     * @param externalPaymentId the HyperSwitch payment ID
     * @param status the payment status
     * @param tenantId the tenant ID
     */
    @ActivityMethod
    void publishPaymentEvent(String paymentId, String externalPaymentId, String status, String tenantId);

    /**
     * Voids/cancels a payment in HyperSwitch.
     *
     * @param externalPaymentId the HyperSwitch payment ID
     */
    @ActivityMethod
    void voidPayment(String externalPaymentId);
}
