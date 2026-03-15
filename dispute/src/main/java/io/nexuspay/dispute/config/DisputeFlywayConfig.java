package io.nexuspay.dispute.config;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the dispute module's Flyway migration path.
 *
 * <p>Adds {@code classpath:db/migration/dispute/} to Flyway's search
 * locations so module-specific migrations are discovered.
 * See GAP-017 for the multi-module Flyway pattern.</p>
 *
 * @since 0.2.4 (Sprint 2.4)
 */
@Configuration
public class DisputeFlywayConfig {

    @Bean
    public FlywayConfigurationCustomizer disputeFlywayCustomizer() {
        return (FluentConfiguration configuration) -> {
            var existingLocations = configuration.getLocations();
            var newLocations = new org.flywaydb.core.api.Location[existingLocations.length + 1];
            System.arraycopy(existingLocations, 0, newLocations, 0, existingLocations.length);
            newLocations[existingLocations.length] =
                    new org.flywaydb.core.api.Location("classpath:db/migration/dispute");
            configuration.locations(newLocations);
        };
    }
}
