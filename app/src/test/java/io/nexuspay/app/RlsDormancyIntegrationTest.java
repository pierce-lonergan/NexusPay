package io.nexuspay.app;

import com.zaxxer.hikari.HikariDataSource;
import io.nexuspay.app.rls.AppTenantTransactionTemplate;
import io.nexuspay.app.rls.InlineTenantWorkRunner;
import io.nexuspay.app.rls.RlsEnforcementConfig;
import io.nexuspay.app.rls.RoleRoutingDataSource;
import io.nexuspay.app.rls.SystemRoleAspect;
import io.nexuspay.common.rls.TenantWorkRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Dormancy guard (B-002): with RLS enforcement OFF (the default), the whole enforcement feature
 * must be out of the bean graph — Spring Boot's auto-configured single owner datasource and
 * default transaction manager are used, exactly as before. If this fails, "dormant" is a lie and
 * the enforcement machinery is changing runtime behavior unintentionally.
 */
class RlsDormancyIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ApplicationContext ctx;

    @Test
    void enforcementDisabled_routingDataSourceAndAspects_areAbsent() {
        assumeTrue(DOCKER_AVAILABLE, "requires Docker (Testcontainers Postgres)");

        DataSource ds = ctx.getBean(DataSource.class);
        assertThat(ds)
                .as("default profile must use the auto-configured single pool, not the role router")
                .isInstanceOf(HikariDataSource.class)
                .isNotInstanceOf(RoleRoutingDataSource.class);

        assertThat(ctx.getBeansOfType(RoleRoutingDataSource.class))
                .as("no RoleRoutingDataSource at enforce=false").isEmpty();
        assertThat(ctx.getBeansOfType(SystemRoleAspect.class))
                .as("no SystemRoleAspect at enforce=false").isEmpty();
        assertThat(ctx.getBeansOfType(RlsEnforcementConfig.class))
                .as("no RlsEnforcementConfig at enforce=false").isEmpty();

        // B-002-activation-tenant: the TenantWorkRunner is wired even when dormant, but it must be
        // the inline (no role-routing, tenant-ignoring) impl — never the RLS-enforcing one.
        assertThat(ctx.getBean(TenantWorkRunner.class))
                .as("dormant TenantWorkRunner must be the inline, non-routing implementation")
                .isInstanceOf(InlineTenantWorkRunner.class);
        assertThat(ctx.getBeansOfType(AppTenantTransactionTemplate.class))
                .as("no AppTenantTransactionTemplate at enforce=false").isEmpty();
    }

    @Test
    void enforcementDisabled_noTableIsForced() throws Exception {
        assumeTrue(DOCKER_AVAILABLE, "requires Docker (Testcontainers Postgres)");
        // C7 dormancy: at the default rlsforce=false, R__rls_force_owner.sql must leave NO table
        // FORCE'd. Critical safety property — the app still connects as the owner here, so a forced
        // table with no tenant GUC would lock the app out of every row (total outage).
        try (Connection c = DriverManager.getConnection(
                nexuspayPg.getJdbcUrl(), nexuspayPg.getUsername(), nexuspayPg.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                             + "WHERE c.relkind = 'r' AND c.relforcerowsecurity "
                             + "AND n.nspname IN ('public','analytics')")) {
            rs.next();
            assertThat(rs.getInt(1))
                    .as("no public/analytics table is FORCE'd while RLS enforcement is off")
                    .isZero();
        }
    }
}
