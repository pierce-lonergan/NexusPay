package io.nexuspay.workflow;

import io.nexuspay.common.exception.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * SEC-27 test-only advice: maps {@link ResourceNotFoundException} → 404, mirroring the production
 * {@code GlobalExceptionHandler} (which lives in gateway-api, off the workflow module's classpath).
 * {@code @WebMvcTest} auto-detects {@code @RestControllerAdvice} beans, so the cross-tenant by-id IDOR
 * controller-slice cases assert the real not-found contract (absent OR wrong-tenant collapsed to 404,
 * no existence oracle).
 */
@RestControllerAdvice
public class WorkflowTestExceptionAdvice {

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void handleNotFound(ResourceNotFoundException e) {
        // 404 with empty body — collapses "absent" and "wrong tenant" into one no-oracle response.
    }
}
