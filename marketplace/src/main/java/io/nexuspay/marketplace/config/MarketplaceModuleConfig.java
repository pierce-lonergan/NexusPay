package io.nexuspay.marketplace.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Root configuration for the marketplace module.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
@Configuration
@EnableConfigurationProperties(MarketplaceProperties.class)
public class MarketplaceModuleConfig {
}
