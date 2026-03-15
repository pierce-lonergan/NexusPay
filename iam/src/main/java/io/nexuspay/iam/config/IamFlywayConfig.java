package io.nexuspay.iam.config;

import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the IAM module's Flyway migration path (GAP-017).
 */
@Configuration
public class IamFlywayConfig {

    @Bean
    FlywayConfigurationCustomizer iamFlywayCustomizer() {
        return configuration -> {
            var existing = configuration.getLocations();
            var iamLocation = "classpath:db/migration/iam";
            for (var loc : existing) {
                if (loc.getDescriptor().equals(iamLocation)) return;
            }
            var result = new String[existing.length + 1];
            for (int i = 0; i < existing.length; i++) {
                result[i] = existing[i].getDescriptor();
            }
            result[existing.length] = iamLocation;
            configuration.locations(result);
        };
    }
}
