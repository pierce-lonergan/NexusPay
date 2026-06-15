package io.nexuspay.gateway.config;

import io.nexuspay.common.domain.ApiError;
import io.nexuspay.common.domain.ApiErrorResponse;
import io.nexuspay.common.exception.AuthorizationException;
import io.nexuspay.common.exception.LedgerException;
import io.nexuspay.common.exception.NexusPayException;
import io.nexuspay.common.exception.PaymentException;
import io.nexuspay.common.exception.ResourceNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PaymentException.class)
    public ResponseEntity<ApiErrorResponse> handlePaymentException(PaymentException ex) {
        log.warn("Payment error: {} [{}]", ex.getMessage(), ex.getErrorCode());
        ApiError error = ApiError.apiError(ex.getErrorCode(), ex.getMessage());
        HttpStatus status = switch (ex.getErrorCode()) {
            case "payment_not_found" -> HttpStatus.NOT_FOUND;
            case "invalid_state" -> HttpStatus.CONFLICT;
            case "cross_border_blocked" -> HttpStatus.FORBIDDEN;       // B-003 sanctions block
            case "fraud_blocked" -> HttpStatus.UNPROCESSABLE_ENTITY;   // B-003 fraud BLOCK
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(ApiErrorResponse.of(error));
    }

    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<ApiErrorResponse> handleLedgerException(LedgerException ex) {
        log.error("Ledger error: {} [{}]", ex.getMessage(), ex.getErrorCode());
        ApiError error = ApiError.apiError(ex.getErrorCode(), ex.getMessage());
        HttpStatus status = switch (ex.getErrorCode()) {
            case "account_not_found" -> HttpStatus.NOT_FOUND;
            case "concurrency_conflict" -> HttpStatus.CONFLICT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(ApiErrorResponse.of(error));
    }

    @ExceptionHandler(AuthorizationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthorizationException(AuthorizationException ex) {
        log.warn("Authorization error: {} [{}]", ex.getMessage(), ex.getErrorCode());
        ApiError error = new ApiError(ApiError.TYPE_AUTHENTICATION, ex.getErrorCode(), ex.getMessage(), null);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiErrorResponse.of(error));
    }

    /**
     * Spring Security throws {@link AccessDeniedException} from method security
     * (@PreAuthorize) and URL rules. Without this handler it falls through to the
     * generic Exception handler and becomes a 500 — leaking an authorization
     * failure as a server error. An authenticated-but-unauthorized caller must
     * get 403.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        ApiError error = new ApiError(ApiError.TYPE_AUTHENTICATION, "access_denied", "Access denied", null);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiErrorResponse.of(error));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .findFirst()
                .orElse(ex.getMessage());
        ApiError error = ApiError.invalidRequest("invalid_parameter", message, null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiErrorResponse.of(error));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        var fieldError = ex.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String message = fieldError != null
                ? fieldError.getField() + ": " + fieldError.getDefaultMessage()
                : ex.getMessage();
        String param = fieldError != null ? fieldError.getField() : null;
        ApiError error = ApiError.invalidRequest("invalid_parameter", message, param);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiErrorResponse.of(error));
    }

    /**
     * Tenant-scoped by-id reads/writes throw {@link ResourceNotFoundException} when the resource is
     * absent OR belongs to another tenant (SEC-BATCH-1). Both map to 404 so a wrong-tenant id is
     * indistinguishable from a non-existent one (no existence oracle). This also fixes the latent
     * bug where "not found" services threw {@code IllegalArgumentException}, which had no handler and
     * fell through to a 500.
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {} [{}]", ex.getMessage(), ex.getErrorCode());
        ApiError error = ApiError.apiError(ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiErrorResponse.of(error));
    }

    @ExceptionHandler(NexusPayException.class)
    public ResponseEntity<ApiErrorResponse> handleGenericNexusPayException(NexusPayException ex) {
        log.error("Unhandled NexusPay exception: {} [{}]", ex.getMessage(), ex.getErrorCode());
        ApiError error = ApiError.apiError(ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiErrorResponse.of(error));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGenericException(Exception ex) {
        log.error("Unexpected error", ex);
        ApiError error = ApiError.apiError("internal_error", "An unexpected error occurred");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiErrorResponse.of(error));
    }
}
