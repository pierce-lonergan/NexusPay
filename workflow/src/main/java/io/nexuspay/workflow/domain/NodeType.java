package io.nexuspay.workflow.domain;

/**
 * Types of nodes available in the visual workflow builder.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
public enum NodeType {
    /** Entry point — triggers workflow execution. */
    TRIGGER,

    /** Creates a payment via the payment orchestration layer. */
    PAYMENT,

    /** Conditional branching (if/else) on expression evaluation. */
    CONDITION,

    /** Splits flow into parallel branches. */
    SPLIT,

    /** Delays execution for a configurable duration. */
    DELAY,

    /** Sends an outbound webhook to an external URL. */
    WEBHOOK,

    /** Sends a notification (email, Slack, SMS). */
    NOTIFICATION,

    /** Executes a sandboxed expression (JSONLogic). */
    CUSTOM_SCRIPT,

    /** Terminal node — ends the workflow. */
    END
}
