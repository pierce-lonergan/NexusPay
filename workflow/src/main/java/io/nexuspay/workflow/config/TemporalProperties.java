package io.nexuspay.workflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Temporal workflow engine.
 *
 * @since 0.2.0 (Sprint 2.2)
 */
@ConfigurationProperties(prefix = "nexuspay.temporal")
public record TemporalProperties(
        String target,
        String namespace,
        String taskQueue,
        int workerThreads
) {
    public TemporalProperties {
        if (target == null) target = "localhost:7233";
        if (namespace == null) namespace = "default";
        if (taskQueue == null) taskQueue = "nexuspay-main";
        if (workerThreads <= 0) workerThreads = 4;
    }
}
