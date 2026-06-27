package io.nexuspay.iam.domain;

import io.nexuspay.common.tenant.LiveModePrincipal;
import io.nexuspay.common.tenant.ScopedPrincipal;
import io.nexuspay.common.tenant.TenantPrincipal;

import java.util.Set;

/**
 * Uniform principal produced by JWT (Keycloak), API key, or session token authentication.
 * Available via SecurityContext in all downstream code.
 *
 * <p>Session-scoped principals (from checkout SDK) include a {@code sessionId} that
 * restricts them to operations on a single payment session.
 *
 * <p>Implements {@link TenantPrincipal} (a {@code common} contract) so that {@code :common}-only
 * modules can read the caller's tenant via {@code CallerTenant.require()} without importing this
 * iam type. The {@code tenantId()} record accessor already satisfies the interface — zero
 * behaviour change.
 *
 * <p><b>DX-5c-ii scopes semantics (authorization control — read carefully):</b> {@code scopes} is the
 * set of API scopes ({@code resource:action}) this principal is RESTRICTED to. The semantics are
 * deliberately chosen so that the common cases all mean UNRESTRICTED and only an explicitly-scoped API
 * key is restricted:
 * <ul>
 *   <li>{@code scopes == null} → UNRESTRICTED (role-based). This is the DEFAULT for every JWT, session,
 *       and pre-DX-5c-ii API-key principal — the back-compat path is byte-identical and unrestricted.</li>
 *   <li>{@code scopes} is an EMPTY set → ALSO UNRESTRICTED. Treating empty as "locked out" would be a
 *       foot-gun (an accidentally-empty set would deny everything), so empty is normalized to the same
 *       meaning as null.</li>
 *   <li>{@code scopes} is a NON-EMPTY set → RESTRICTED to exactly those scopes. The principal may only
 *       perform an operation whose required scope is a member of this set; scopes NARROW the role and
 *       never widen it.</li>
 * </ul>
 * Only {@code ApiKeyService.authenticate} ever passes a non-null/non-empty set (parsed from the key's
 * persisted {@code scopes} csv). Use {@link #hasScope(String)} for the fail-closed check.
 */
public record NexusPayPrincipal(
        String userId,
        String tenantId,
        String role,
        AuthMethod authMethod,
        String sessionId,
        boolean live,         // INT-3: server-derived from the API key's is_live (true for JWT/OIDC)
        Set<String> scopes    // DX-5c-ii: null/empty = UNRESTRICTED; non-empty = restricted to these
) implements TenantPrincipal, ScopedPrincipal, LiveModePrincipal {
    /**
     * Constructor without sessionId (backward-compatible for JWT and API key auth).
     *
     * <p>INT-3: {@code live} defaults to {@code true} — a non-API-key console actor (Keycloak/OIDC) is
     * a real/LIVE principal. The ONLY path that sets {@code live=false} is an authenticated
     * {@code sk_test_} key (see {@code ApiKeyService.authenticate}).</p>
     *
     * <p>DX-5c-ii: {@code scopes} defaults to {@code null} (UNRESTRICTED) — the back-compat default.</p>
     */
    public NexusPayPrincipal(String userId, String tenantId, String role, AuthMethod authMethod) {
        this(userId, tenantId, role, authMethod, null, true, null);
    }

    /**
     * Constructor with sessionId but no explicit mode (backward-compatible for session tokens).
     * INT-3: {@code live} defaults to {@code true} (a session actor is real).
     * DX-5c-ii: {@code scopes} defaults to {@code null} (UNRESTRICTED).
     */
    public NexusPayPrincipal(String userId, String tenantId, String role,
                             AuthMethod authMethod, String sessionId) {
        this(userId, tenantId, role, authMethod, sessionId, true, null);
    }

    /**
     * DX-5c-ii: 6-arg constructor (with sessionId + live, no scopes) — backward-compatible for every
     * pre-DX-5c-ii call site (the API-key path before scopes, plus all existing test fixtures). Defaults
     * {@code scopes} to {@code null} (UNRESTRICTED) so the default principal path stays byte-identical.
     */
    public NexusPayPrincipal(String userId, String tenantId, String role,
                             AuthMethod authMethod, String sessionId, boolean live) {
        this(userId, tenantId, role, authMethod, sessionId, live, null);
    }

    public enum AuthMethod {
        JWT,
        API_KEY,
        SESSION_TOKEN
    }

    /**
     * DX-5c-ii FAIL-CLOSED scope check: does this principal carry {@code scope}?
     *
     * <p>Returns {@code true} when the principal is UNRESTRICTED ({@code scopes} null or empty — the
     * back-compat / role-based case) OR when {@code scopes} explicitly contains {@code scope}. Returns
     * {@code false} for a RESTRICTED principal that lacks the scope. This narrows, never widens: an
     * unrestricted key still passes the role check in the composed {@code @PreAuthorize}; a restricted
     * key additionally needs the matching scope.</p>
     *
     * @param scope the required scope (a member of the ApiScope vocabulary)
     * @return {@code true} if the operation is permitted by this principal's scope set
     */
    @Override
    public boolean hasScope(String scope) {
        if (scopes == null || scopes.isEmpty()) {
            return true; // UNRESTRICTED (role-based) — back-compat
        }
        return scopes.contains(scope);
    }

    public boolean isAdmin() {
        return "admin".equals(role);
    }

    public boolean isOperator() {
        return "operator".equals(role);
    }

    public boolean isViewer() {
        return "viewer".equals(role);
    }

    /**
     * Returns {@code true} if this principal was authenticated via a session token
     * and is scoped to a specific payment session.
     *
     * @since 0.3.5 (Sprint 3.5)
     */
    public boolean isSessionScoped() {
        return authMethod == AuthMethod.SESSION_TOKEN && sessionId != null;
    }
}
