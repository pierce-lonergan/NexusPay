package io.nexuspay.gateway.config;

import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the gateway module's Flyway migration path (GAP-017).
 */
@Configuration
public class GatewayFlywayConfig {

    @Bean
    FlywayConfigurationCustomizer gatewayFlywayCustomizer() {
        return configuration -> {
            var existing = configuration.getLocations();
            var gatewayLocation = "classpath:db/migration/gateway";
            for (var loc : existing) {
                if (loc.getDescriptor().equals(gatewayLocation)) return;
            }
            var result = new String[existing.length + 1];
            for (int i = 0; i < existing.length; i++) {
                result[i] = existing[i].getDescriptor();
            }
            result[existing.length] = gatewayLocation;
            configuration.locations(result);
        };
    }
}
