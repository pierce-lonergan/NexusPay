package io.nexuspay.app.rls;

import io.nexuspay.common.rls.TenantWorkRunner;
import io.nexuspay.iam.domain.TenantContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * RLS-enforcing {@link TenantWorkRunner} (B-002): runs a unit of work in a fresh transaction pinned
 * to the {@code nexuspay_app} role and bound to one tenant, so PostgreSQL RLS (USING + WITH CHECK)
 * guards every read and write. Active ONLY under {@code nexuspay.multi-tenancy.rls.enforce=true};
 * the dormant {@link InlineTenantWorkRunner} takes its place otherwise.
 *
 * <p>Mechanism: {@link RlsRoutingTransactionManager#doBegin} reads {@link DbRoleContext} and
 * {@link TenantContext} AT transaction begin, so both must be set BEFORE {@code execute}. We use
 * {@code PROPAGATION_REQUIRES_NEW} so this works standalone AND from inside a
 * {@link io.nexuspay.common.rls.SystemTransactional} sweep — the new transaction suspends the outer
 * (SYSTEM) one and acquires a fresh {@code nexuspay_app} connection
 * ({@link RoleRoutingDataSource} keys on {@link DbRoleContext} per {@code getConnection}). The prior
 * role and tenant are captured and restored in {@code finally}, so nesting is safe.</p>
 */
@Component
@ConditionalOnProperty(name = "nexuspay.multi-tenancy.rls.enforce", havingValue = "true")
public class AppTenantTransactionTemplate implements TenantWorkRunner {

    private final TransactionTemplate requiresNew;

    public AppTenantTransactionTemplate(PlatformTransactionManager transactionManager) {
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void runInTenant(String tenantId, Runnable work) {
        callInTenant(tenantId, () -> {
            work.run();
            return null;
        });
    }

    @Override
    public <T> T callInTenant(String tenantId, Supplier<T> work) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required for an APP-bound (RLS) transaction");
        }
        DbRole previousRole = DbRoleContext.get();      // may be SYSTEM (inside a sweep) — capture, don't assume
        String previousTenant = TenantContext.get();
        DbRoleContext.set(DbRole.APP);                  // pin APP BEFORE the new transaction begins
        TenantContext.set(tenantId);                    // bind tenant BEFORE the new transaction begins
        try {
            return requiresNew.execute(status -> work.get());
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
            DbRoleContext.set(previousRole);
        }
    }
}
