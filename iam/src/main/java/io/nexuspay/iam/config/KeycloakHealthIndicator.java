package io.nexuspay.iam.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Health indicator for Keycloak connectivity.
 * Calls the Keycloak realm endpoint and reports UP/DOWN.
 */
@Component
public class KeycloakHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(KeycloakHealthIndicator.class);

    private final RestClient restClient;

    public KeycloakHealthIndicator(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri) {
        this.restClient = RestClient.builder()
                .baseUrl(issuerUri)
                .build();
    }

    @Override
    public Health health() {
        try {
            restClient.get()
                    .uri("")
                    .retrieve()
                    .toBodilessEntity();
            return Health.up()
                    .withDetail("service", "Keycloak")
                    .build();
        } catch (Exception e) {
            log.warn("Keycloak health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("service", "Keycloak")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
