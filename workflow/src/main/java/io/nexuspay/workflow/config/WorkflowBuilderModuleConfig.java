package io.nexuspay.workflow.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Root configuration for the workflow builder module.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
@Configuration
@EnableConfigurationProperties(WorkflowBuilderProperties.class)
public class WorkflowBuilderModuleConfig {
}
