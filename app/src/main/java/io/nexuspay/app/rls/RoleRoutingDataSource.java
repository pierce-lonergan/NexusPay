package io.nexuspay.app.rls;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Routes each {@code getConnection()} to the {@code nexuspay_app} or {@code nexuspay_system}
 * pool based on the calling thread's {@link DbRoleContext} (B-002). A single DataSource bean →
 * a single EntityManagerFactory → all repositories work unchanged; only the physical connection
 * (and thus the DB role / RLS behavior) varies per thread.
 *
 * <p>The lookup key is resolved lazily on the same thread {@code JpaTransactionManager.doBegin}
 * runs on, so the role pinned by {@link SystemRoleAspect} (before tx begin) is in effect when
 * the connection is acquired.</p>
 */
public class RoleRoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        return DbRoleContext.get();
    }
}
