package io.nexuspay.common.event.consumer;

import java.util.List;

/**
 * Interface for batch event consumers that process multiple events in a single invocation.
 * Used with Kafka batch listeners for high-throughput consumption scenarios.
 *
 * <p>Batch consumers receive up to {@code batchSize} events per invocation (default 50).
 * Implementations should handle partial failures gracefully — either process all-or-nothing
 * or track individual failures and report them.
 *
 * @param <T> the event type (typically Map&lt;String, Object&gt; or a domain event type)
 * @since 0.3.0 (Sprint 3.4)
 */
public interface BatchEventConsumer<T> {

    /**
     * Process a batch of events.
     *
     * @param events the batch of events to process (never null, may be empty)
     */
    void processBatch(List<T> events);

    /**
     * The event type this consumer handles (e.g. "PaymentCaptured").
     * Used for routing events to the correct batch consumer.
     *
     * @return the event type string, or "*" to receive all event types
     */
    String eventType();
}
