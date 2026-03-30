package io.nexuspay.workflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Workflow builder module configuration properties bound to {@code nexuspay.workflow-builder.*}.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
@Configuration
@ConfigurationProperties(prefix = "nexuspay.workflow-builder")
public class WorkflowBuilderProperties {

    private boolean enabled = true;
    private ExpressionConfig expression = new ExpressionConfig();
    private ExecutionConfig execution = new ExecutionConfig();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public ExpressionConfig getExpression() { return expression; }
    public void setExpression(ExpressionConfig expression) { this.expression = expression; }

    public ExecutionConfig getExecution() { return execution; }
    public void setExecution(ExecutionConfig execution) { this.execution = execution; }

    public static class ExpressionConfig {
        private String engine = "jsonlogic";
        private int maxExpressionLength = 4096;

        public String getEngine() { return engine; }
        public void setEngine(String engine) { this.engine = engine; }

        public int getMaxExpressionLength() { return maxExpressionLength; }
        public void setMaxExpressionLength(int maxExpressionLength) { this.maxExpressionLength = maxExpressionLength; }
    }

    public static class ExecutionConfig {
        private int maxConcurrentExecutions = 100;
        private long executionTimeoutMs = 300000;

        public int getMaxConcurrentExecutions() { return maxConcurrentExecutions; }
        public void setMaxConcurrentExecutions(int maxConcurrentExecutions) { this.maxConcurrentExecutions = maxConcurrentExecutions; }

        public long getExecutionTimeoutMs() { return executionTimeoutMs; }
        public void setExecutionTimeoutMs(long executionTimeoutMs) { this.executionTimeoutMs = executionTimeoutMs; }
    }
}
