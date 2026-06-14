package io.nexuspay.app.rls;

/**
 * Database connection role for {@link RoleRoutingDataSource} (B-002 RLS activation).
 *
 * <ul>
 *   <li>{@link #APP} — the RLS-bound application role ({@code nexuspay_app}); the per-request
 *       default. Tenant isolation is enforced by the {@code app.current_tenant_id} GUC set on
 *       each transaction; with no tenant bound it sees ZERO rows (fail-closed).
 *   <li>{@link #SYSTEM} — the {@code BYPASSRLS} role ({@code nexuspay_system}) used only by
 *       cross-tenant background jobs / consumers via {@link io.nexuspay.common.rls.SystemTransactional}.
 * </ul>
 *
 * <p>The migration/owner role ({@code nexuspay}) is intentionally NOT a routing target — it is
 * used only by Flyway.</p>
 */
public enum DbRole {
    APP,
    SYSTEM
}
