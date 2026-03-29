package io.nexuspay.workflow.domain;

/**
 * Status of a workflow execution instance.
 *
 * @since 0.4.3 (Sprint 4.4)
 */
public enum ExecutionStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT
}
