package io.nexuspay.common.exception;

/**
 * Thrown when a by-id resource is either truly absent OR belongs to a different tenant than the
 * caller. Collapsing both cases into a single not-found path is deliberate: returning 403 (rather
 * than 404) on a wrong-tenant id would give an attacker an existence oracle, letting them enumerate
 * which ids exist in other tenants. The HTTP layer maps this to {@code 404 Not Found}
 * (see {@code GlobalExceptionHandler}).
 *
 * @since SEC-BATCH-1
 */
public class ResourceNotFoundException extends NexusPayException {

    public ResourceNotFoundException(String message) {
        super(message, "resource_not_found");
    }

    public static ResourceNotFoundException of(String resourceType, String id) {
        return new ResourceNotFoundException(resourceType + " not found: " + id);
    }
}
