package io.nexuspay.b2b.domain;

/**
 * Lifecycle states for a virtual card.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
public enum VirtualCardStatus {
    ACTIVE,
    FROZEN,
    CANCELLED,
    EXPIRED
}
