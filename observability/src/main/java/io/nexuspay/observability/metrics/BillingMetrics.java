package io.nexuspay.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Custom Micrometer metrics for subscription billing.
 *
 * @since 0.2.7 (Sprint 2.7)
 */
@Component
public class BillingMetrics {

    private final MeterRegistry registry;
    private final AtomicLong activeSubscriptions = new AtomicLong(0);

    public BillingMetrics(MeterRegistry registry) {
        this.registry = registry;

        Gauge.builder("nexuspay.subscriptions.active", activeSubscriptions, AtomicLong::get)
                .description("Current active subscriptions")
                .register(registry);
    }

    public void setActiveSubscriptions(long count) {
        activeSubscriptions.set(count);
    }

    public void recordSubscriptionChurned(String reason) {
        Counter.builder("nexuspay.subscriptions.churned")
                .description("Subscriptions canceled")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    public void recordTrialConverted() {
        Counter.builder("nexuspay.subscriptions.trial.converted")
                .description("Trials converted to active")
                .register(registry)
                .increment();
    }

    public void recordDunningAttempt(String outcome) {
        Counter.builder("nexuspay.dunning.attempts")
                .description("Dunning retry attempts")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void recordDisputeOpened(String reason) {
        Counter.builder("nexuspay.disputes.opened")
                .description("Disputes opened")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }
}
