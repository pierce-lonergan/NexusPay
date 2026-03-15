package io.nexuspay.fraud.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Root configuration for the fraud module.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(FraudProperties.class)
public class FraudModuleConfig {
}
