package io.nexuspay.iam.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Health indicator for HashiCorp Vault connectivity.
 *
 * <p>Checks Vault's /v1/sys/health endpoint. Only active when Vault
 * integration is enabled (spring.cloud.vault.enabled=true or explicit property).</p>
 *
 * @since 0.2.0 (Sprint 2.1)
 */
@Component
@ConditionalOnProperty(name = "nexuspay.vault.health.enabled", havingValue = "true", matchIfMissing = false)
public class VaultHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(VaultHealthIndicator.class);

    private final RestClient restClient;
    private final String vaultUrl;

    public VaultHealthIndicator(
            @Value("${spring.cloud.vault.uri:http://localhost:8200}") String vaultUrl) {
        this.vaultUrl = vaultUrl;
        this.restClient = RestClient.builder()
                .baseUrl(vaultUrl)
                .build();
    }

    @Override
    public Health health() {
        try {
            var response = restClient.get()
                    .uri("/v1/sys/health")
                    .retrieve()
                    .toEntity(String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                return Health.up()
                        .withDetail("url", vaultUrl)
                        .withDetail("status", "active")
                        .build();
            }

            // 429 = standby node, 472 = DR secondary, 473 = perf standby
            // All are valid in HA setups
            int status = response.getStatusCode().value();
            if (status == 429 || status == 472 || status == 473) {
                return Health.up()
                        .withDetail("url", vaultUrl)
                        .withDetail("status", "standby")
                        .build();
            }

            return Health.down()
                    .withDetail("url", vaultUrl)
                    .withDetail("httpStatus", status)
                    .build();
        } catch (Exception e) {
            log.warn("Vault health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("url", vaultUrl)
                    .withException(e)
                    .build();
        }
    }
}
