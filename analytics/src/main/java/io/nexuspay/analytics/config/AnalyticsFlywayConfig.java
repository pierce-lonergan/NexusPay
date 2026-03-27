package io.nexuspay.analytics.config;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the analytics module's Flyway migration path.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
@Configuration
public class AnalyticsFlywayConfig {

    @Bean
    public FlywayConfigurationCustomizer analyticsFlywayCustomizer() {
        return (FluentConfiguration configuration) -> {
            var existingLocations = configuration.getLocations();
            var newLocations = new org.flywaydb.core.api.Location[existingLocations.length + 1];
            System.arraycopy(existingLocations, 0, newLocations, 0, existingLocations.length);
            newLocations[existingLocations.length] =
                    new org.flywaydb.core.api.Location("classpath:db/migration/analytics");
            configuration.locations(newLocations);
        };
    }
}
