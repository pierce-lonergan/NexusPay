package io.nexuspay.b2b.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Root configuration for the B2B payments module.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
@Configuration
@EnableConfigurationProperties(B2bProperties.class)
public class B2bModuleConfig {
}
