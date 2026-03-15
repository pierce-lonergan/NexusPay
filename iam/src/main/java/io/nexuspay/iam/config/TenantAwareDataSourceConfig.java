package io.nexuspay.iam.config;

import io.nexuspay.iam.domain.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.ConnectionHandle;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.lang.NonNull;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Decorates the DataSource to inject {@code SET LOCAL app.current_tenant_id = ?}
 * on every connection checkout, enabling PostgreSQL Row-Level Security (RLS).
 *
 * <p>{@code SET LOCAL} is scoped to the current transaction — when the transaction
 * commits or rolls back, the setting is automatically reverted. This is critical
 * for connection pool correctness: connections returned to HikariCP's pool do not
 * retain the tenant context from a previous request.</p>
 *
 * <p>If no tenant context is set (e.g., system jobs, migrations, health checks),
 * the decorator is a no-op and the connection operates as the superuser role,
 * which bypasses RLS policies.</p>
 *
 * <p>Enabled by default, can be disabled with {@code nexuspay.multi-tenancy.rls.enabled=false}
 * for testing or single-tenant deployments.</p>
 *
 * @since 0.2.0 (Sprint 2.1)
 */
@Configuration
@ConditionalOnProperty(name = "nexuspay.multi-tenancy.rls.enabled", havingValue = "true", matchIfMissing = true)
public class TenantAwareDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareDataSourceConfig.class);
    private static final String SET_TENANT_SQL = "SET LOCAL app.current_tenant_id = ?";
    private static final String RESET_TENANT_SQL = "RESET app.current_tenant_id";

    /**
     * Wraps the auto-configured DataSource with tenant-aware connection decoration.
     * Uses {@link LazyConnectionDataSourceProxy} to defer the actual connection
     * acquisition until the first statement, then injects the tenant context.
     */
    @Bean
    public DataSource tenantAwareDataSource(DataSource originalDataSource) {
        log.info("Enabling tenant-aware DataSource with RLS support");
        return new TenantAwareDataSource(originalDataSource);
    }

    /**
     * DataSource wrapper that intercepts {@code getConnection()} to inject
     * the tenant context via {@code SET LOCAL}.
     */
    static class TenantAwareDataSource implements DataSource {

        private final DataSource delegate;

        TenantAwareDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection conn = delegate.getConnection();
            injectTenantContext(conn);
            return conn;
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            Connection conn = delegate.getConnection(username, password);
            injectTenantContext(conn);
            return conn;
        }

        private void injectTenantContext(Connection conn) throws SQLException {
            String tenantId = TenantContext.get();
            if (tenantId != null) {
                try (PreparedStatement stmt = conn.prepareStatement(SET_TENANT_SQL)) {
                    stmt.setString(1, tenantId);
                    stmt.execute();
                }
                if (log.isTraceEnabled()) {
                    log.trace("Set tenant context on connection: {}", tenantId);
                }
            }
            // If no tenant context, connection operates as superuser (bypasses RLS)
        }

        // Delegate all other DataSource methods

        @Override
        public java.io.PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getLogger(java.util.logging.Logger.GLOBAL_LOGGER_NAME);
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return iface.isInstance(this) || delegate.isWrapperFor(iface);
        }
    }
}
