package io.nexuspay.common.exception;

/**
 * Thrown when a caller-supplied request targets a resource whose CURRENT STATE forbids the requested
 * operation — e.g. rotating an API key that is already revoked/expired or already superseded. The HTTP
 * layer maps this to {@code 409 Conflict} (see {@code GlobalExceptionHandler}).
 *
 * <p>This is a CALLER-caused condition (a 4xx), distinct from a plain {@link IllegalStateException},
 * which the codebase reserves for INTERNAL invariant violations / programming errors and deliberately
 * leaves mapped to {@code 500} (a server fault that must trip 5xx alerts). Throwing this typed domain
 * exception — rather than a raw {@code IllegalStateException} caught by a blanket advice — keeps that
 * 100-plus internal-throw blast radius at 500 while giving genuine state-conflicts a correct 409.</p>
 *
 * <p>Messages MUST be safe code-with-text (no secrets / no cross-tenant oracle): the handler echoes the
 * message on the 4xx body, the same contract as {@link ResourceNotFoundException} / {@code PaymentException}.</p>
 *
 * @since DX-5c
 */
public class ConflictException extends NexusPayException {

    public ConflictException(String message) {
        super(message, "conflict");
    }

    public ConflictException(String message, String errorCode) {
        super(message, errorCode);
    }
}
