package io.nexuspay.payment.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Health indicator for HyperSwitch connectivity.
 * Calls GET /health on HyperSwitch and reports UP/DOWN.
 * Contributes to the aggregate /actuator/health endpoint.
 *
 * <p>Default-on; disable with {@code management.health.hyperSwitch.enabled=false}
 * (e.g. integration tests with no PSP, where a connection-refused would otherwise
 * mark the whole /actuator/health DOWN).</p>
 */
@Component
@ConditionalOnEnabledHealthIndicator("hyperSwitch")
public class HyperSwitchHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(HyperSwitchHealthIndicator.class);
    private final RestClient restClient;

    public HyperSwitchHealthIndicator(RestClient hyperSwitchRestClient) {
        this.restClient = hyperSwitchRestClient;
    }

    @Override
    public Health health() {
        try {
            restClient.get()
                    .uri("/health")
                    .retrieve()
                    .toBodilessEntity();
            return Health.up()
                    .withDetail("service", "HyperSwitch")
                    .build();
        } catch (Exception e) {
            log.warn("HyperSwitch health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("service", "HyperSwitch")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
