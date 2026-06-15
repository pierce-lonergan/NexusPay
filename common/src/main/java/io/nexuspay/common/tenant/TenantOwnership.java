package io.nexuspay.common.tenant;

import io.nexuspay.common.exception.ResourceNotFoundException;

import java.util.Optional;
import java.util.function.Function;

/**
 * Reusable ownership-assertion helper for tenant-scoped by-id reads/writes.
 *
 * <p>Returns the loaded entity only when it is present AND its tenant equals the caller's tenant;
 * otherwise throws {@link ResourceNotFoundException}. Collapsing "absent" and "wrong tenant" into a
 * single not-found path avoids an existence oracle (see {@link ResourceNotFoundException}).</p>
 *
 * <p>Prefer pairing this with a tenant-scoped finder ({@code findByIdAndTenantId}) so the predicate
 * is pushed to SQL and no foreign-tenant row ever leaves the database. For entities reached through a
 * tenant-scoped parent, a post-load assertion via {@link #assertTenant} is acceptable.</p>
 *
 * @since SEC-BATCH-1
 */
public final class TenantOwnership {

    private TenantOwnership() {
        // Utility class
    }

    /**
     * Unwraps a tenant-scoped lookup. Use with a {@code findByIdAndTenantId}-style finder whose
     * empty result already means "absent OR not owned".
     *
     * @param loaded       result of a tenant-scoped finder
     * @param resourceType human-readable type for the not-found message (no id/oracle leakage)
     */
    public static <T> T require(Optional<T> loaded, String resourceType) {
        return loaded.orElseThrow(() -> new ResourceNotFoundException(resourceType + " not found"));
    }

    /**
     * Unwraps a global lookup and asserts ownership in code (for the load-then-assert pattern where a
     * tenant-scoped finder is not available).
     *
     * @param loaded       result of a global (by-id) finder
     * @param callerTenant the authenticated caller's tenant
     * @param ownerOf      extracts the owning tenant from the entity
     * @param resourceType human-readable type for the not-found message
     */
    public static <T> T assertOwned(Optional<T> loaded, String callerTenant,
                                    Function<T, String> ownerOf, String resourceType) {
        T entity = loaded
                .filter(e -> callerTenant != null && callerTenant.equals(ownerOf.apply(e)))
                .orElseThrow(() -> new ResourceNotFoundException(resourceType + " not found"));
        return entity;
    }

    /**
     * Asserts an already-loaded entity belongs to the caller tenant, throwing not-found on mismatch.
     * Use when the entity was reached through a tenant-scoped parent but a defence-in-depth check on
     * the child is still warranted.
     */
    public static <T> T assertTenant(T entity, String callerTenant, Function<T, String> ownerOf,
                                     String resourceType) {
        if (entity == null || callerTenant == null || !callerTenant.equals(ownerOf.apply(entity))) {
            throw new ResourceNotFoundException(resourceType + " not found");
        }
        return entity;
    }
}
