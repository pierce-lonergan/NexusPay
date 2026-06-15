package io.nexuspay.fraud.application.service;

/**
 * Thrown when the keyed request fingerprint cannot be computed (master key absent/unusable, or a
 * Mac/MessageDigest failure). This is a FAIL-CLOSED signal: it is NEVER swallowed into a null or a
 * sentinel that could compare-equal to a stored fingerprint. Callers must treat it as "cannot prove
 * the request matches the original" — on the write path it propagates (rolls back, no half-
 * fingerprinted row); on the dedup-hit compare path it means RE-ASSESS, never return the prior
 * (stale) decision. This is the deliberate opposite of the dedup-LOOKUP fail-OPEN posture.
 *
 * @since B-029-hardening
 */
public class FingerprintUnavailableException extends RuntimeException {

    public FingerprintUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
