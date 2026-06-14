package io.nexuspay.app;

import io.nexuspay.app.rls.SystemTransactional;
import io.nexuspay.iam.domain.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves the B-002 RLS enforcement is REAL when active (the gate the JDBC-only
 * {@link RlsIsolationIntegrationTest} could not reach — this exercises the actual Spring
 * mechanism: {@code RlsRoutingTransactionManager} sets the GUC per transaction on the
 * {@code nexuspay_app} pool, {@code @SystemTransactional} routes to {@code nexuspay_system}).
 *
 * <p>Runs under the {@code rls-enforce} profile with app traffic on {@code nexuspay_app} and
 * Flyway on the container superuser. Separate from {@link IntegrationTestBase}'s default
 * (superuser) context so the dormant suite is unaffected. Self-skips without Docker.</p>
 */
@ActiveProfiles({"test", "rls-enforce"})
@Import(RlsEnforceIntegrationTest.RlsProbe.class)
class RlsEnforceIntegrationTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void enforce(DynamicPropertyRegistry r) {
        r.add("nexuspay.multi-tenancy.rls.enforce", () -> "true");
        r.add("nexuspay.multi-tenancy.rls.app-username", () -> "nexuspay_app");
        r.add("nexuspay.multi-tenancy.rls.app-password", () -> "nexuspay_app_local");
        r.add("nexuspay.multi-tenancy.rls.system-username", () -> "nexuspay_system");
        r.add("nexuspay.multi-tenancy.rls.system-password", () -> "nexuspay_system_local");
        // Flyway as the container superuser (owns schema, BYPASSRLS):
        r.add("spring.flyway.user", nexuspayPg::getUsername);
        r.add("spring.flyway.password", nexuspayPg::getPassword);
        r.add("spring.flyway.url", nexuspayPg::getJdbcUrl);
    }

    @Autowired
    private RlsProbe probe;

    @Test
    void appRoleIsolatesTenants_systemRoleBypasses_unboundFailsClosed() throws Exception {
        assumeTrue(DOCKER_AVAILABLE, "requires Docker (Testcontainers Postgres)");
        seedAsOwner();
        try {
            // APP role + tenant GUC set by RlsRoutingTransactionManager.doBegin → isolation
            TenantContext.set("en_tA");
            assertThat(probe.appVisibleTenants())
                    .as("bound to tenant A, the app role sees only A").containsExactly("en_tA");

            TenantContext.set("en_tB");
            assertThat(probe.appVisibleTenants())
                    .as("bound to tenant B, the app role sees only B").containsExactly("en_tB");

            // Fail-closed: no tenant bound → no GUC → zero rows
            TenantContext.clear();
            assertThat(probe.appVisibleTenants())
                    .as("no tenant bound → fail closed (zero rows)").isEmpty();

            // SYSTEM role (@SystemTransactional) bypasses RLS → sees every tenant
            assertThat(probe.systemVisibleTenants())
                    .as("@SystemTransactional runs on the BYPASSRLS role and sees all tenants")
                    .containsExactlyInAnyOrder("en_tA", "en_tB");
        } finally {
            TenantContext.clear();
            cleanupAsOwner();
        }
    }

    private void seedAsOwner() throws Exception {
        try (Connection c = DriverManager.getConnection(
                nexuspayPg.getJdbcUrl(), nexuspayPg.getUsername(), nexuspayPg.getPassword())) {
            try (Statement st = c.createStatement()) {
                st.execute("DELETE FROM ledger_accounts WHERE tenant_id IN ('en_tA','en_tB')");
            }
            insert(c, "la_en_a", "en_tA");
            insert(c, "la_en_b", "en_tB");
        }
    }

    private void cleanupAsOwner() throws Exception {
        try (Connection c = DriverManager.getConnection(
                nexuspayPg.getJdbcUrl(), nexuspayPg.getUsername(), nexuspayPg.getPassword());
             Statement st = c.createStatement()) {
            st.execute("DELETE FROM ledger_accounts WHERE tenant_id IN ('en_tA','en_tB')");
        }
    }

    private static void insert(Connection c, String id, String tenant) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ledger_accounts (id, name, type, currency, tenant_id) VALUES (?, ?, 'ASSET', 'USD', ?)")) {
            ps.setString(1, id);
            ps.setString(2, "enforce test " + tenant);
            ps.setString(3, tenant);
            ps.executeUpdate();
        }
    }

    /**
     * Test probe registered as a bean via {@code @Import}, so its {@code @Transactional} /
     * {@code @SystemTransactional} methods are AOP-proxied and exercise the real
     * RlsRoutingTransactionManager + SystemRoleAspect end-to-end.
     */
    static class RlsProbe {
        @PersistenceContext
        private EntityManager em;

        @Transactional(readOnly = true)
        public List<String> appVisibleTenants() {
            return distinctTestTenants();
        }

        @SystemTransactional
        @Transactional(readOnly = true)
        public List<String> systemVisibleTenants() {
            return distinctTestTenants();
        }

        @SuppressWarnings("unchecked")
        private List<String> distinctTestTenants() {
            return em.createNativeQuery(
                            "SELECT DISTINCT tenant_id FROM ledger_accounts "
                                    + "WHERE tenant_id IN ('en_tA','en_tB') ORDER BY tenant_id")
                    .getResultList();
        }
    }
}
