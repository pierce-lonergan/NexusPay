package io.nexuspay.ledger.config;

import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the ledger module's Flyway migration path (GAP-017).
 */
@Configuration
public class LedgerFlywayConfig {

    @Bean
    FlywayConfigurationCustomizer ledgerFlywayCustomizer() {
        return configuration -> {
            var existing = configuration.getLocations();
            var ledgerLocation = "classpath:db/migration/ledger";
            for (var loc : existing) {
                if (loc.getDescriptor().equals(ledgerLocation)) return;
            }
            var result = new String[existing.length + 1];
            for (int i = 0; i < existing.length; i++) {
                result[i] = existing[i].getDescriptor();
            }
            result[existing.length] = ledgerLocation;
            configuration.locations(result);
        };
    }
}
