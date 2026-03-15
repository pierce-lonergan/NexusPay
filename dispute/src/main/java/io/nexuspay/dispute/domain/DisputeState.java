package io.nexuspay.dispute.domain;

/**
 * Dispute lifecycle states.
 *
 * <pre>
 * OPENED → EVIDENCE_NEEDED → EVIDENCE_SUBMITTED → WON
 *                                                → LOST
 *                                                → EXPIRED
 * </pre>
 *
 * @since 0.2.4 (Sprint 2.4)
 */
public enum DisputeState {

    /** Dispute opened — chargeback reserve created in ledger. */
    OPENED,

    /** Evidence requested by card network — deadline timer active. */
    EVIDENCE_NEEDED,

    /** Evidence submitted to network — awaiting decision. */
    EVIDENCE_SUBMITTED,

    /** Dispute won — chargeback reversed, funds restored. */
    WON,

    /** Dispute lost — chargeback finalized as expense. */
    LOST,

    /** Evidence deadline passed without submission. */
    EXPIRED;

    /** Returns {@code true} if this state is terminal (no further transitions). */
    public boolean isTerminal() {
        return this == WON || this == LOST || this == EXPIRED;
    }
}
