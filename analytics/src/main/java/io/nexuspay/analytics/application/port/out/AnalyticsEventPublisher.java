package io.nexuspay.analytics.application.port.out;

import io.nexuspay.analytics.domain.event.AnomalyDetected;
import io.nexuspay.analytics.domain.event.PspHealthDegraded;

/**
 * Out-port for publishing analytics domain events.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
public interface AnalyticsEventPublisher {

    void publish(PspHealthDegraded event);

    void publish(AnomalyDetected event);
}
