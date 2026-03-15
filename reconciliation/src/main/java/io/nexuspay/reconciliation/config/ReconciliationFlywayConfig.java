package io.nexuspay.reconciliation.config;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the reconciliation module's Flyway migration path.
 *
 * <p>Adds {@code classpath:db/migration/reconciliation/} to Flyway's
 * search locations so module-specific migrations are discovered.
 * See GAP-017 for the multi-module Flyway pattern.</p>
 *
 * @since 0.2.0 (Sprint 2.3)
 */
@Configuration
public class ReconciliationFlywayConfig {

    @Bean
    public FlywayConfigurationCustomizer reconciliationFlywayCustomizer() {
        return (FluentConfiguration configuration) -> {
            var existingLocations = configuration.getLocations();
            var newLocations = new org.flywaydb.core.api.Location[existingLocations.length + 1];
            System.arraycopy(existingLocations, 0, newLocations, 0, existingLocations.length);
            newLocations[existingLocations.length] =
                    new org.flywaydb.core.api.Location("classpath:db/migration/reconciliation");
            configuration.locations(newLocations);
        };
    }
}
