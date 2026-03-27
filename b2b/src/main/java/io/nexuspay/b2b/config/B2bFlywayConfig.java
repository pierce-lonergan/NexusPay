package io.nexuspay.b2b.config;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the B2B module's Flyway migration path.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
@Configuration
public class B2bFlywayConfig {

    @Bean
    public FlywayConfigurationCustomizer b2bFlywayCustomizer() {
        return (FluentConfiguration configuration) -> {
            var existingLocations = configuration.getLocations();
            var newLocations = new org.flywaydb.core.api.Location[existingLocations.length + 1];
            System.arraycopy(existingLocations, 0, newLocations, 0, existingLocations.length);
            newLocations[existingLocations.length] =
                    new org.flywaydb.core.api.Location("classpath:db/migration/b2b");
            configuration.locations(newLocations);
        };
    }
}
