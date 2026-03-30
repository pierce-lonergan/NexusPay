package io.nexuspay.workflow.config;

import org.springframework.context.annotation.Configuration;

/**
 * Workflow builder security configuration placeholder.
 * Expression sandboxing, webhook HMAC verification, and execution
 * resource quotas will be enforced here.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
@Configuration
public class WorkflowBuilderSecurityConfig {
    // Expression sandboxing (JSONLogic only, no SpEL eval), webhook HMAC, execution quotas
}
