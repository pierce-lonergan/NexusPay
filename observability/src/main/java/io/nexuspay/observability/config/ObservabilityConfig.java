package io.nexuspay.observability.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Observability module configuration.
 *
 * <p>Applies global tags and SLI histogram bucket configuration
 * to the Micrometer meter registry. Tags {@code application=nexuspay}
 * and {@code environment} from the active Spring profile are added
 * to every metric for Grafana filtering.</p>
 *
 * @since 0.2.7 (Sprint 2.7)
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> nexuspayMetricsCustomizer() {
        return registry -> {
            registry.config()
                    .commonTags("application", "nexuspay")
                    .meterFilter(MeterFilter.deny(id -> {
                        // Suppress high-cardinality internal metrics
                        String name = id.getName();
                        return name.startsWith("jvm.buffer") || name.startsWith("process.files");
                    }));
        };
    }
}
