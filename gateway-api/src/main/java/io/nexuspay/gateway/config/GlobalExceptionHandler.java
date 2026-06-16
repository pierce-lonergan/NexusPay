package io.nexuspay.gateway.config;

import io.nexuspay.common.domain.ApiError;
import io.nexuspay.common.domain.ApiErrorResponse;
import io.nexuspay.common.exception.AuthorizationException;
import io.nexuspay.common.exception.LedgerException;
import io.nexuspay.common.exception.NexusPayException;
import io.nexuspay.common.exception.PaymentException;
import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.gateway.adapter.in.filter.CorrelationIdFilter;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.UUID;

/**
 * INT-2 error contract. Every handler emits the stable envelope
 * {@code { "error": { type, code, message, request_id } }} via {@link #body}; the HTTP status codes are
 * UNCHANGED from the pre-INT-2 behavior. {@code request_id} echoes the {@link CorrelationIdFilter}
 * correlation id (with a UUID fallback so it is never null).
 *
 * <p>Leak hardening: any 500 emits a generic {@code "An unexpected error occurred"} message — the real
 * exception message/stack is only logged, never put on the wire. Curated 4xx domain messages
 * ({@code PaymentException}, {@code ResourceNotFoundException}, {@code AuthorizationException}, ledger
 * 404/409) remain because they are safe code-with-text.</p>
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String GENERIC_500_MESSAGE = "An unexpected error occurred";

    /** Correlation id for the error body — sourced from MDC, with a UUID fallback so it is never null. */
    private static String requestId() {
        String id = MDC.get(CorrelationIdFilter.MDC_REQUEST_ID);
        return (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
    }

    private static ResponseEntity<ApiErrorResponse> body(HttpStatus status, String type, String code, String message) {
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.of(ApiError.of(type, code, message, requestId())));
    }

    @ExceptionHandler(PaymentException.class)
    public ResponseEntity<ApiErrorResponse> handlePaymentException(PaymentException ex) {
        log.warn("Payment error: {} [{}]", ex.getMessage(), ex.getErrorCode());
        HttpStatus status = switch (ex.getErrorCode()) {
            case "payment_not_found" -> HttpStatus.NOT_FOUND;
            case "invalid_state" -> HttpStatus.CONFLICT;
            case "cross_border_blocked" -> HttpStatus.FORBIDDEN;       // B-003 sanctions block
            case "fraud_blocked" -> HttpStatus.UNPROCESSABLE_ENTITY;   // B-003 fraud BLOCK
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return body(status, ApiError.TYPE_PAYMENT, ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<ApiErrorResponse> handleLedgerException(LedgerException ex) {
        log.error("Ledger error: {} [{}]", ex.getMessage(), ex.getErrorCode());
        HttpStatus status = switch (ex.getErrorCode()) {
            case "account_not_found" -> HttpStatus.NOT_FOUND;
            case "concurrency_conflict" -> HttpStatus.CONFLICT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            // Leak hardening: 500 bodies never echo the domain message.
            return body(status, ApiError.TYPE_INTERNAL, ex.getErrorCode(), GENERIC_500_MESSAGE);
        }
        return body(status, ApiError.TYPE_PAYMENT, ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(AuthorizationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthorizationException(AuthorizationException ex) {
        log.warn("Authorization error: {} [{}]", ex.getMessage(), ex.getErrorCode());
        return body(HttpStatus.FORBIDDEN, ApiError.TYPE_FORBIDDEN, ex.getErrorCode(), ex.getMessage());
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
        return body(HttpStatus.FORBIDDEN, ApiError.TYPE_FORBIDDEN, "access_denied", "Access denied");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .findFirst()
                .orElse(ex.getMessage());
        return body(HttpStatus.BAD_REQUEST, ApiError.TYPE_VALIDATION, "invalid_parameter", message);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        var fieldError = ex.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        // INT-2: the field hint is folded into the message (the wire no longer carries a separate param).
        String message = fieldError != null
                ? fieldError.getField() + ": " + fieldError.getDefaultMessage()
                : ex.getMessage();
        return body(HttpStatus.BAD_REQUEST, ApiError.TYPE_VALIDATION, "invalid_parameter", message);
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
        return body(HttpStatus.NOT_FOUND, ApiError.TYPE_NOT_FOUND, ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(NexusPayException.class)
    public ResponseEntity<ApiErrorResponse> handleGenericNexusPayException(NexusPayException ex) {
        // Leak hardening: log the real message/code, but never echo ex.getMessage() into a 500 body.
        log.error("Unhandled NexusPay exception: {} [{}]", ex.getMessage(), ex.getErrorCode());
        return body(HttpStatus.INTERNAL_SERVER_ERROR, ApiError.TYPE_INTERNAL, "internal_error", GENERIC_500_MESSAGE);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGenericException(Exception ex) {
        log.error("Unexpected error", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, ApiError.TYPE_INTERNAL, "internal_error", GENERIC_500_MESSAGE);
    }
}
