package io.nexuspay.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Custom Micrometer metrics for payment operations.
 *
 * <p>Tracks payment creation, authorization, capture, failure counts,
 * and latency histograms per PSP connector and currency.</p>
 *
 * @since 0.2.7 (Sprint 2.7)
 */
@Component
public class PaymentMetrics {

    private final MeterRegistry registry;

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Records a payment creation event.
     */
    public void recordPaymentCreated(String psp, String currency, String status) {
        Counter.builder("nexuspay.payments.created")
                .description("Total payments created")
                .tag("psp", psp)
                .tag("currency", currency)
                .tag("status", status)
                .register(registry)
                .increment();
    }

    /**
     * Records payment processing latency.
     */
    public void recordPaymentLatency(String psp, long durationMs) {
        Timer.builder("nexuspay.payments.latency")
                .description("Payment processing latency")
                .tag("psp", psp)
                .serviceLevelObjectives(
                        Duration.ofMillis(50),
                        Duration.ofMillis(100),
                        Duration.ofMillis(250),
                        Duration.ofMillis(500),
                        Duration.ofMillis(1000),
                        Duration.ofMillis(2000)
                )
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Records a payment failure.
     */
    public void recordPaymentFailed(String psp, String errorCode) {
        Counter.builder("nexuspay.payments.failed")
                .description("Total payment failures")
                .tag("psp", psp)
                .tag("error_code", errorCode != null ? errorCode : "unknown")
                .register(registry)
                .increment();
    }

    /**
     * Records a refund event.
     */
    public void recordRefund(String psp, String currency, String status) {
        Counter.builder("nexuspay.refunds.total")
                .description("Total refunds")
                .tag("psp", psp)
                .tag("currency", currency)
                .tag("status", status)
                .register(registry)
                .increment();
    }
}
