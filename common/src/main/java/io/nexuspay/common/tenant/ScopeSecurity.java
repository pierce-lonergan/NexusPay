package io.nexuspay.common.tenant;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * DX-5c-ii: the enforcement bean consulted from {@code @PreAuthorize} SpEL as {@code @scopeAuth.has(...)}.
 *
 * <p>Registered as {@code @Component("scopeAuth")}. Reads the current authentication principal from the
 * {@link SecurityContextHolder} and answers, FAIL-CLOSED, whether the caller carries a required scope.
 * Composed with the existing role check using {@code and} in each guarded endpoint's expression, e.g.
 * {@code @PreAuthorize("hasRole('operator') and @scopeAuth.has('payments:write')")} — scopes NARROW the
 * role, never replace it.</p>
 *
 * <p><b>Why this lives in {@code common}, not gateway-api:</b> the money-critical controllers it guards
 * are spread across modules that depend ONLY on {@code :common} (marketplace, vault, dispute) and CANNOT
 * import the iam {@code NexusPayPrincipal}. Like {@link CallerTenant}, this bean works off a
 * {@code common} contract ({@link ScopedPrincipal}, which {@code NexusPayPrincipal} implements) so a
 * single {@code @scopeAuth} bean is resolvable from every module's {@code @PreAuthorize} SpEL.</p>
 *
 * <p><b>Fail-closed decision table:</b></p>
 * <ul>
 *   <li>No authentication / not authenticated / null principal → {@code false} (cannot positively
 *       confirm the scope behind an authenticated endpoint → DENY).</li>
 *   <li>Principal is a {@link ScopedPrincipal} (the production API-key / JWT / session principal) →
 *       delegates to {@link ScopedPrincipal#hasScope(String)} (UNRESTRICTED ⇒ true; RESTRICTED ⇒ true
 *       only for a granted scope).</li>
 *   <li>Principal is authenticated but NOT a {@code ScopedPrincipal} (e.g. a non-scope-bearing principal)
 *       → {@code true}: such a principal carries NO scope set and is therefore UNRESTRICTED by the same
 *       null/empty == unrestricted semantics used throughout DX-5c-ii. Only an explicitly-scoped API key
 *       (a {@code ScopedPrincipal} with a non-empty set) is ever restricted; this preserves the default
 *       (back-compat) path unchanged for every non-API-key principal.</li>
 * </ul>
 *
 * @since DX-5c-ii
 */
@Component("scopeAuth")
public class ScopeSecurity {

    /**
     * @param scope the required scope (a member of the {@code io.nexuspay.common.api.ApiScope} vocabulary)
     * @return {@code true} iff the current authenticated caller is permitted that scope; FAIL-CLOSED
     *         ({@code false}) when there is no authenticated principal to confirm it.
     */
    public boolean has(String scope) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false; // no positive confirmation possible → DENY
        }
        Object principal = auth.getPrincipal();
        if (principal == null) {
            return false; // fail closed
        }
        if (principal instanceof ScopedPrincipal scoped) {
            // The production path: API-key principals carry a (possibly null/empty == unrestricted) scope
            // set; hasScope is itself fail-closed for a restricted key that lacks the scope.
            return scoped.hasScope(scope);
        }
        // An authenticated, non-scope-bearing principal carries no scope set → UNRESTRICTED (back-compat).
        return true;
    }
}
