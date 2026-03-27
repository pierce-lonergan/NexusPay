package io.nexuspay.analytics.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Root configuration for the analytics module.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AnalyticsProperties.class)
public class AnalyticsModuleConfig {
}
