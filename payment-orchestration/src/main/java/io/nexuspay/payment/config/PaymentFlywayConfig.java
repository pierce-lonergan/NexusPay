package io.nexuspay.payment.config;

import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Registers the payment module's Flyway migration path.
 * Each module self-registers its migrations so new modules don't require
 * manual updates to application.yml (GAP-017).
 */
@Configuration
public class PaymentFlywayConfig {

    @Bean
    FlywayConfigurationCustomizer paymentFlywayCustomizer() {
        return configuration -> {
            var locations = new ArrayList<>(Arrays.asList(configuration.getLocations()));
            var paymentLocation = "classpath:db/migration/payment";
            boolean alreadyPresent = locations.stream()
                    .anyMatch(loc -> loc.getDescriptor().equals(paymentLocation));
            if (!alreadyPresent) {
                configuration.locations(
                        append(configuration.getLocations(), paymentLocation));
            }
        };
    }

    private static String[] append(org.flywaydb.core.api.Location[] existing, String newLocation) {
        var result = new String[existing.length + 1];
        for (int i = 0; i < existing.length; i++) {
            result[i] = existing[i].getDescriptor();
        }
        result[existing.length] = newLocation;
        return result;
    }
}
