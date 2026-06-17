package io.nexuspay.reconciliation;

import io.nexuspay.common.exception.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * SEC-27 test-only advice: maps {@link ResourceNotFoundException} → 404, mirroring the production
 * {@code GlobalExceptionHandler} (which lives in gateway-api/iam, off reconciliation's classpath).
 * {@code @WebMvcTest} auto-detects {@code @RestControllerAdvice} beans in the configuration package,
 * so the cross-tenant by-id IDOR controller-slice cases assert the real not-found contract (no
 * existence oracle: "absent" and "wrong tenant" collapse into one 404 with empty body).
 */
@RestControllerAdvice
public class ReconciliationTestExceptionAdvice {

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void handleNotFound(ResourceNotFoundException e) {
        // 404 with empty body — collapses "absent" and "wrong tenant" into one no-oracle response.
    }
}
