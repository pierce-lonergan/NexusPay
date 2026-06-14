package io.nexuspay.common.rls;

import java.util.function.Supplier;

/**
 * Runs a unit of work in a NEW transaction bound to a single tenant on the RLS-bound application
 * role (B-002 activation). The implementation sets the tenant context and pins the APP database
 * role BEFORE the transaction begins, so PostgreSQL Row-Level Security (both USING and WITH CHECK)
 * scopes every read and write in {@code work} to {@code tenantId}.
 *
 * <p>Lives in {@code common} (a pure {@code String} + {@code Runnable}/{@code Supplier} contract,
 * no iam/app imports) so any module — billing, ledger, analytics, gateway-api — can depend on it.
 * The acting implementation lives in the {@code app} composition root (the only module that can see
 * both the routing {@code DbRoleContext} and {@code TenantContext}), exactly like
 * {@link SystemTransactional}.</p>
 *
 * <p>Use for the two cases RLS enforcement requires a bound tenant on the APP role:
 * <ul>
 *   <li>a Kafka consumer processing ONE tenant's event (bind from the event, then do the DB work);</li>
 *   <li>a cross-tenant background sweep that discovers rows as SYSTEM but must write each item under
 *       its own tenant — call this per item so RLS WITH CHECK guards the write.</li>
 * </ul>
 * It opens a {@code REQUIRES_NEW} transaction, so it is safe to call from inside a
 * {@link SystemTransactional} (SYSTEM-pinned) method: the new transaction switches to the APP role
 * for its duration and the SYSTEM pin is restored afterward.</p>
 *
 * <p>Dormant when enforcement is off: the {@code enforce=false} implementation simply opens a normal
 * {@code REQUIRES_NEW} transaction and ignores {@code tenantId}, so call sites are structurally
 * identical in both modes.</p>
 */
public interface TenantWorkRunner {

    /** Runs {@code work} in a new transaction bound to {@code tenantId} on the APP role. */
    void runInTenant(String tenantId, Runnable work);

    /** Runs {@code work} in a new transaction bound to {@code tenantId} on the APP role, returning its result. */
    <T> T callInTenant(String tenantId, Supplier<T> work);

    /**
     * Binds {@code tenantId} on the APP role for the duration of {@code work} WITHOUT opening an outer
     * transaction. Any {@code @Transactional} method called within then opens its OWN tenant-bound
     * transaction at its OWN declared isolation — use this when the inner work already manages its
     * transaction(s) and an enclosing default-isolation transaction would silently downgrade them (e.g.
     * the ledger's {@code SERIALIZABLE} journal write). When YOU need the helper to own the transaction
     * boundary (a consumer whose handlers are not themselves transactional, or a per-item sweep write),
     * use {@link #runInTenant}/{@link #callInTenant} instead. Dormant impl: just runs {@code work}.
     */
    void bindTenant(String tenantId, Runnable work);
}
