package io.nexuspay.app.rls;

import io.nexuspay.iam.domain.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * Sets the {@code app.current_tenant_id} GUC on each transaction, deterministically (B-002).
 *
 * <p>Overriding {@code doBegin} (rather than relying on AOP advice ordering relative to the tx
 * interceptor) guarantees the GUC is set <em>after</em> the transaction has begun and the
 * connection is bound to the thread — eliminating the ordering gamble. {@code set_config(...,
 * true)} is transaction-local, so it auto-reverts on commit/rollback and cannot leak across
 * pooled connections.</p>
 *
 * <p>APP role only: SYSTEM connections are {@code BYPASSRLS} (no GUC needed). If no tenant is
 * bound on an APP transaction, the GUC stays unset → {@code current_tenant_id()} is NULL →
 * policies match zero rows (fail-closed; never resolved to a privileged value).</p>
 */
public class RlsRoutingTransactionManager extends JpaTransactionManager {

    private static final Logger log = LoggerFactory.getLogger(RlsRoutingTransactionManager.class);
    private static final String SET_TENANT = "SELECT set_config('app.current_tenant_id', :tenant, true)";

    public RlsRoutingTransactionManager(EntityManagerFactory emf) {
        super(emf);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        if (DbRoleContext.get() != DbRole.APP) {
            return; // SYSTEM = BYPASSRLS, no GUC
        }
        String tenant = TenantContext.get();
        if (tenant == null || tenant.isBlank()) {
            return; // fail-closed: no GUC → current_tenant_id() NULL → zero rows
        }
        EntityManager em = EntityManagerFactoryUtils.getTransactionalEntityManager(getEntityManagerFactory());
        if (em == null) {
            // Should not happen after super.doBegin; do not silently proceed unscoped.
            throw new IllegalStateException("RLS: no transactional EntityManager bound to set tenant GUC");
        }
        em.createNativeQuery(SET_TENANT).setParameter("tenant", tenant).getSingleResult();
        if (log.isTraceEnabled()) {
            log.trace("RLS tenant GUC set for transaction: {}", tenant);
        }
    }
}
