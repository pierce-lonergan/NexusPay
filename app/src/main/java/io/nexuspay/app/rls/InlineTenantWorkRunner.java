package io.nexuspay.app.rls;

import io.nexuspay.common.rls.TenantWorkRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * Dormant-mode {@link TenantWorkRunner} (B-002): the active implementation when RLS enforcement is
 * OFF (the default). There is no role routing and no tenant GUC in this mode, so it simply runs the
 * work in a normal {@code REQUIRES_NEW} transaction and IGNORES {@code tenantId}.
 *
 * <p>Keeping the {@code REQUIRES_NEW} boundary identical to {@link AppTenantTransactionTemplate}
 * means every call site has byte-identical transactional structure in both modes — only the role and
 * GUC differ when enforcement is on. This is what keeps the whole tenant-binding feature inert at
 * {@code enforce=false} (mutually exclusive with the enforcing impl via {@code @ConditionalOnProperty}).</p>
 */
@Component
@ConditionalOnProperty(name = "nexuspay.multi-tenancy.rls.enforce", havingValue = "false", matchIfMissing = true)
public class InlineTenantWorkRunner implements TenantWorkRunner {

    private final TransactionTemplate requiresNew;

    public InlineTenantWorkRunner(PlatformTransactionManager transactionManager) {
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void runInTenant(String tenantId, Runnable work) {
        requiresNew.executeWithoutResult(status -> work.run());
    }

    @Override
    public <T> T callInTenant(String tenantId, Supplier<T> work) {
        return requiresNew.execute(status -> work.get());
    }

    @Override
    public void bindTenant(String tenantId, Runnable work) {
        // Dormant: no role/tenant binding and (unlike runInTenant) no enclosing transaction either,
        // so inner @Transactional methods open their own transactions exactly as before the feature.
        work.run();
    }
}
