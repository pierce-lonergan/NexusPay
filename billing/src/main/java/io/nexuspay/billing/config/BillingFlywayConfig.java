package io.nexuspay.billing.config;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the billing module's Flyway migration path.
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
@Configuration
public class BillingFlywayConfig {

    @Bean
    public FlywayConfigurationCustomizer billingFlywayCustomizer() {
        return (FluentConfiguration configuration) -> {
            var existingLocations = configuration.getLocations();
            var newLocations = new org.flywaydb.core.api.Location[existingLocations.length + 1];
            System.arraycopy(existingLocations, 0, newLocations, 0, existingLocations.length);
            newLocations[existingLocations.length] =
                    new org.flywaydb.core.api.Location("classpath:db/migration/billing");
            configuration.locations(newLocations);
        };
    }
}
