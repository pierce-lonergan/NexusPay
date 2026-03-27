package io.nexuspay.vault.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Root configuration for the vault module.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
@Configuration
@EnableConfigurationProperties(VaultProperties.class)
public class VaultModuleConfig {
}
