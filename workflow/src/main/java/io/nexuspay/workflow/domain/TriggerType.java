package io.nexuspay.workflow.domain;

/**
 * Types of workflow triggers (entry points).
 *
 * @since 0.4.3 (Sprint 4.4)
 */
public enum TriggerType {
    /** Triggered by an inbound webhook HTTP request. */
    WEBHOOK,

    /** Triggered by a domain event (e.g., PaymentCreated). */
    EVENT,

    /** Triggered on a cron schedule. */
    SCHEDULE,

    /** Triggered manually via API call. */
    MANUAL
}
