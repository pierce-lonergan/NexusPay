package io.nexuspay.common.exception;

/**
 * Thrown when a caller-supplied argument is semantically invalid at the service boundary — e.g. an API
 * key {@code expiresAt} that is at-or-before now. The HTTP layer maps this to {@code 400 Bad Request}
 * (see {@code GlobalExceptionHandler}).
 *
 * <p>This is a CALLER-caused condition (a 4xx), distinct from a plain {@link IllegalArgumentException},
 * which the codebase reserves for INTERNAL invariant violations / programming errors and deliberately
 * leaves mapped to {@code 500}. Most bad inputs are already rejected at the controller boundary by Bean
 * Validation ({@code @Future} / {@code @NotBlank} → {@code MethodArgumentNotValidException}); this typed
 * exception is the defence-in-depth service-layer guard, so a violation surfaces as a correct 400 rather
 * than masquerading as a server fault — without a blanket {@code IllegalArgumentException} advice that
 * would also downgrade genuine programming-error throws to 400.</p>
 *
 * <p>Named to avoid clashing with {@code jakarta.validation.ValidationException}.</p>
 *
 * @since DX-5c
 */
public class InvalidRequestException extends NexusPayException {

    public InvalidRequestException(String message) {
        super(message, "invalid_request");
    }

    public InvalidRequestException(String message, String errorCode) {
        super(message, errorCode);
    }
}
