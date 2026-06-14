package io.nexuspay.app.rls;

/**
 * Thread-local holder for the current {@link DbRole}, read by {@link RoleRoutingDataSource}
 * to pick the physical connection pool per {@code getConnection()} (B-002).
 *
 * <p>Defaults to {@link DbRole#APP} — the fail-closed choice: any code path that forgets to
 * pin {@code SYSTEM} and has no tenant bound returns zero rows under RLS, never a silent
 * cross-tenant bypass. Virtual-thread safe (each virtual thread has its own ThreadLocal).</p>
 */
public final class DbRoleContext {

    private static final ThreadLocal<DbRole> ROLE = ThreadLocal.withInitial(() -> DbRole.APP);

    private DbRoleContext() {
    }

    public static DbRole get() {
        return ROLE.get();
    }

    /**
     * Sets the current role directly. Prefer {@link #runAs} for scoped pinning; this lower-level
     * setter exists for {@code AppTenantTransactionTemplate}, which must interleave role save/restore
     * with {@code TenantContext} save/restore (which a nesting {@code runAs} can't express cleanly).
     * Callers MUST restore the previous value in a {@code finally}.
     */
    public static void set(DbRole role) {
        ROLE.set(role);
    }

    /** Runs {@code body} with the given role pinned, restoring the previous role afterward. */
    public static void runAs(DbRole role, ThrowingRunnable body) throws Throwable {
        DbRole previous = ROLE.get();
        ROLE.set(role);
        try {
            body.run();
        } finally {
            ROLE.set(previous);
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
