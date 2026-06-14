package io.nexuspay.app;

import io.nexuspay.common.rls.SystemTransactional;
import io.nexuspay.common.rls.TenantWorkRunner;
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

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        // B-004 StartupSecretsValidator treats 'rls-enforce' as a production-like profile
        // (an unrecognized, non-dev profile fails safe → production). Supply NON-default
        // managed secrets so the guard passes WITHOUT weakening it — these live only here,
        // never in application-rls-enforce.yml (that profile is also the real prod cutover
        // and must keep failing closed on the built-in dev defaults).
        r.add("nexuspay.session.jwt-secret", () -> "rls-enforce-it-managed-jwt-secret-0123456789abcdef");
        r.add("nexuspay.hyperswitch.webhook-secret", () -> "rls-enforce-it-managed-webhook-secret");
        r.add("nexuspay.vault.encryption.master-key",
                () -> Base64.getEncoder().encodeToString(
                        "rls-enforce-it-managed-vault-key-32bytes!".getBytes(StandardCharsets.UTF_8)));
    }

    @Autowired
    private RlsProbe probe;
    @Autowired
    private TenantWorkRunner tenantWork;

    @Test
    void tenantWorkRunner_bindsAppTenant_enforcesWithCheck_andNestsUnderSystemPin() throws Exception {
        assumeTrue(DOCKER_AVAILABLE, "requires Docker (Testcontainers Postgres)");
        cleanupAsOwner(); // start clean (this test seeds via the helper, not as owner)
        try {
            // (a) APP role bound to en_tA: a write whose tenant_id matches passes RLS WITH CHECK.
            tenantWork.runInTenant("en_tA", () -> probe.insertTestAccount("la_hw_a", "en_tA"));
            assertThat(probe.systemVisibleTenants())
                    .as("helper write under en_tA is committed and visible to SYSTEM").contains("en_tA");

            // (b) a read bound to en_tA via the helper sees only A (RLS USING on the app role).
            assertThat(tenantWork.callInTenant("en_tA", probe::rawDistinctTestTenants))
                    .as("helper read bound to en_tA sees only en_tA").containsExactly("en_tA");

            // (c) mis-bind: bound to en_tA but writing an en_tB row → RLS WITH CHECK rejects it.
            assertThatThrownBy(() ->
                    tenantWork.runInTenant("en_tA", () -> probe.insertTestAccount("la_hw_bad", "en_tB")))
                    .as("WITH CHECK rejects a write whose tenant_id != the bound tenant");

            // (d) nesting: invoked from inside a @SystemTransactional method, the helper still
            //     switches to APP+tenant for its write (the billing-sweep case) and restores SYSTEM.
            probe.systemThenTenantWrite("la_hw_b", "en_tB");
            assertThat(probe.systemVisibleTenants())
                    .as("helper under a SYSTEM pin writes under the bound tenant; rejected mis-bind left no row")
                    .containsExactlyInAnyOrder("en_tA", "en_tB");
        } finally {
            TenantContext.clear();
            cleanupAsOwner();
        }
    }

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

    /**
     * STRUCTURAL proof only: asserts the migration set relforcerowsecurity on every RLS table.
     * It CANNOT prove FORCE behaviorally binds the owner here, because the Testcontainer owner is a
     * SUPERUSER and superusers bypass even FORCE ROW LEVEL SECURITY. The residual owner-bypass that
     * FORCE closes is realised only against a NON-superuser prod owner; the runtime isolation that
     * actually matters is already proven behaviorally above via the non-owner nexuspay_app role.
     */
    @Test
    void forceRowLevelSecurity_appliedToEveryRlsTable() throws Exception {
        assumeTrue(DOCKER_AVAILABLE, "requires Docker (Testcontainers Postgres)");
        // C7: under the rls-enforce profile, application-rls-enforce.yml sets the rlsforce
        // placeholder to true, so R__rls_force_owner.sql FORCEs every RLS-enabled table —
        // binding even the owner to RLS (defense-in-depth on top of the non-owner app role).
        try (Connection c = DriverManager.getConnection(
                nexuspayPg.getJdbcUrl(), nexuspayPg.getUsername(), nexuspayPg.getPassword());
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                            + "WHERE c.relkind = 'r' AND c.relrowsecurity AND NOT c.relforcerowsecurity "
                            + "AND n.nspname IN ('public','analytics')")) {
                rs.next();
                assertThat(rs.getInt(1))
                        .as("every RLS-enabled public/analytics table is FORCE'd under rls-enforce")
                        .isZero();
            }
            // Guard against a vacuous 0-of-0 pass: a known RLS table must actually be forced.
            try (ResultSet rs = st.executeQuery(
                    "SELECT relforcerowsecurity FROM pg_class WHERE relname = 'ledger_accounts'")) {
                assertThat(rs.next()).as("ledger_accounts row exists in pg_class").isTrue();
                assertThat(rs.getBoolean(1))
                        .as("ledger_accounts has FORCE ROW LEVEL SECURITY").isTrue();
            }
        }
    }

    @Test
    void analyticsRlsPolicies_areFailClosed() throws Exception {
        assumeTrue(DOCKER_AVAILABLE, "requires Docker (Testcontainers Postgres)");
        // Review finding #3 / V3022: analytics policies must use the current_tenant_id() helper
        // (= current_setting(...,true), missing_ok) so an unbound APP txn fail-closes to ZERO ROWS
        // rather than raising 'unrecognized configuration parameter'. Bare current_setting (no
        // missing_ok) would render in pg_policies.qual WITHOUT the helper call.
        try (Connection c = DriverManager.getConnection(
                nexuspayPg.getJdbcUrl(), nexuspayPg.getUsername(), nexuspayPg.getPassword());
             Statement st = c.createStatement()) {
            // Non-vacuous: there ARE analytics tenant policies.
            try (ResultSet rs = st.executeQuery(
                    "SELECT count(*) FROM pg_policies WHERE schemaname = 'analytics'")) {
                rs.next();
                assertThat(rs.getInt(1)).as("analytics has RLS policies to check").isGreaterThan(0);
            }
            // Every analytics policy's USING (and WITH CHECK) must go through the missing_ok helper.
            try (ResultSet rs = st.executeQuery(
                    "SELECT count(*) FROM pg_policies WHERE schemaname = 'analytics' "
                            + "AND (qual NOT LIKE '%current_tenant_id()%' "
                            + "     OR (with_check IS NOT NULL AND with_check NOT LIKE '%current_tenant_id()%'))")) {
                rs.next();
                assertThat(rs.getInt(1))
                        .as("no analytics policy uses bare current_setting (all fail-closed via current_tenant_id())")
                        .isZero();
            }
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
        @Autowired
        private TenantWorkRunner tenantWork;

        @Transactional(readOnly = true)
        public List<String> appVisibleTenants() {
            return distinctTestTenants();
        }

        @SystemTransactional
        @Transactional(readOnly = true)
        public List<String> systemVisibleTenants() {
            return distinctTestTenants();
        }

        /** Native insert that joins the CALLER's transaction (no @Transactional) — used inside the helper's tx. */
        public void insertTestAccount(String id, String tenant) {
            em.createNativeQuery(
                            "INSERT INTO ledger_accounts (id, name, type, currency, tenant_id) "
                                    + "VALUES (?1, ?2, 'ASSET', 'USD', ?3)")
                    .setParameter(1, id)
                    .setParameter(2, "helper test " + tenant)
                    .setParameter(3, tenant)
                    .executeUpdate();
        }

        /** Read that joins the CALLER's transaction — used inside a helper-bound read. */
        public List<String> rawDistinctTestTenants() {
            return distinctTestTenants();
        }

        /** Proves the helper still routes to APP+tenant when invoked from inside a SYSTEM pin (the sweep case). */
        @SystemTransactional
        public void systemThenTenantWrite(String id, String tenant) {
            tenantWork.runInTenant(tenant, () -> insertTestAccount(id, tenant));
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
