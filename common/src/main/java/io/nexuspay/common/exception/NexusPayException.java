package io.nexuspay.common.exception;

/**
 * Base exception for all NexusPay domain exceptions.
 */
public abstract class NexusPayException extends RuntimeException {

    private final String errorCode;

    protected NexusPayException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    protected NexusPayException(String message, String errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
