package io.nexuspay.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for HyperSwitch integration.
 * Bound to nexuspay.hyperswitch.* in application.yml.
 */
@ConfigurationProperties(prefix = "nexuspay.hyperswitch")
public record HyperSwitchProperties(
        String baseUrl,
        String apiKey,
        int connectTimeoutMs,
        int readTimeoutMs
) {
    public HyperSwitchProperties {
        if (connectTimeoutMs <= 0) connectTimeoutMs = 5000;
        if (readTimeoutMs <= 0) readTimeoutMs = 30000;
    }
}
