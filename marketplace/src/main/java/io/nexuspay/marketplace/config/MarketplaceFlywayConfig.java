package io.nexuspay.marketplace.config;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the marketplace module's Flyway migration path.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
@Configuration
public class MarketplaceFlywayConfig {

    @Bean
    public FlywayConfigurationCustomizer marketplaceFlywayCustomizer() {
        return (FluentConfiguration configuration) -> {
            var existingLocations = configuration.getLocations();
            var newLocations = new org.flywaydb.core.api.Location[existingLocations.length + 1];
            System.arraycopy(existingLocations, 0, newLocations, 0, existingLocations.length);
            newLocations[existingLocations.length] =
                    new org.flywaydb.core.api.Location("classpath:db/migration/marketplace");
            configuration.locations(newLocations);
        };
    }
}
