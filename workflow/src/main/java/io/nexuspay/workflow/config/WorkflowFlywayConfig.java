package io.nexuspay.workflow.config;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the workflow module's Flyway migration path.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
@Configuration
public class WorkflowFlywayConfig {

    @Bean
    public FlywayConfigurationCustomizer workflowFlywayCustomizer() {
        return (FluentConfiguration configuration) -> {
            var existingLocations = configuration.getLocations();
            var newLocations = new org.flywaydb.core.api.Location[existingLocations.length + 1];
            System.arraycopy(existingLocations, 0, newLocations, 0, existingLocations.length);
            newLocations[existingLocations.length] =
                    new org.flywaydb.core.api.Location("classpath:db/migration/workflow");
            configuration.locations(newLocations);
        };
    }
}
