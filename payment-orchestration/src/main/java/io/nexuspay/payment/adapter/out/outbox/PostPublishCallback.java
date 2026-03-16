package io.nexuspay.payment.adapter.out.outbox;

/**
 * Callback invoked by {@link OutboxRelay} after a successful Kafka publish.
 * Used to append the published event to the event log for audit trail.
 *
 * <p>Implemented in the app module to bridge OutboxRelay (payment-orchestration)
 * with EventLogAppender (app), avoiding a circular dependency.
 */
@FunctionalInterface
public interface PostPublishCallback {

    /**
     * Called after an outbox event is successfully published to Kafka.
     *
     * @param event the published outbox event
     */
    void onPublished(OutboxEvent event);
}
