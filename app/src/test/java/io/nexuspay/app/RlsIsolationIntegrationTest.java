package io.nexuspay.app;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves PostgreSQL Row-Level Security (migration V2001) ACTUALLY isolates tenants
 * when a connection uses the non-owner {@code nexuspay_app} role with the tenant GUC
 * set per session/transaction — the acceptance gate for B-002.
 *
 * <p><b>The production gap this pins down.</b> The app's main datasource currently
 * authenticates as the table OWNER ({@code NEXUSPAY_DB_USER=nexuspay}), which
 * BYPASSES RLS. So although these policies are correct (proven here against the
 * non-owner role), they do not yet enforce in the running application. Activating
 * enforcement — switch request traffic to {@code nexuspay_app}, set the GUC inside
 * the transaction, and give the ~16 cross-tenant background jobs a system path — is
 * the remaining B-002 work. This IT is the gate that change must pass and the guard
 * against silently regressing to "RLS bypassed".</p>
 *
 * <p>Test-only: it opens raw JDBC connections to the shared Testcontainers Postgres
 * and does not touch the application datasource, so it cannot affect runtime
 * behaviour. Self-skips without Docker.</p>
 */
class RlsIsolationIntegrationTest extends IntegrationTestBase {

    private static final String APP_ROLE = "nexuspay_app";
    private static final String APP_PW = "nexuspay_app_local";   // set by V2001

    @Test
    void rlsPolicies_isolateTenants_andFailClosed_underNonOwnerRole() throws Exception {
        assumeTrue(DOCKER_AVAILABLE, "requires Docker (Testcontainers Postgres)");
        final String url = nexuspayPg.getJdbcUrl();

        // --- setup: as the OWNER (bypasses RLS), seed two tenants' accounts ---
        try (Connection owner = DriverManager.getConnection(
                url, nexuspayPg.getUsername(), nexuspayPg.getPassword())) {
            try (Statement st = owner.createStatement()) {
                st.execute("DELETE FROM ledger_accounts WHERE tenant_id IN ('rls_tA','rls_tB')");
            }
            seedAccount(owner, "la_rls_a", "rls_tA");
            seedAccount(owner, "la_rls_b", "rls_tB");
        }

        // --- proof: as the non-owner app role, RLS must bind ---
        try (Connection app = DriverManager.getConnection(url, APP_ROLE, APP_PW)) {
            // Precondition: a superuser/owner would bypass RLS and invalidate this test.
            assertThat(isSuperuser(app))
                    .as("nexuspay_app must be a non-superuser for RLS to apply")
                    .isFalse();

            bindTenant(app, "rls_tA");
            assertThat(visibleTestTenants(app))
                    .as("bound to tenant A → only A's rows are visible")
                    .containsExactly("rls_tA");

            bindTenant(app, "rls_tB");
            assertThat(visibleTestTenants(app))
                    .as("bound to tenant B → only B's rows are visible")
                    .containsExactly("rls_tB");

            // A cross-tenant write is a silent no-op: bound to A, deleting B's row hits 0 rows.
            bindTenant(app, "rls_tA");
            try (Statement st = app.createStatement()) {
                int affected = st.executeUpdate("DELETE FROM ledger_accounts WHERE id = 'la_rls_b'");
                assertThat(affected).as("tenant A cannot delete tenant B's row").isZero();
            }
        }

        // --- fail-closed: a fresh connection with NO tenant bound sees ZERO rows (not all) ---
        try (Connection app = DriverManager.getConnection(url, APP_ROLE, APP_PW)) {
            assertThat(visibleTestTenants(app))
                    .as("no tenant bound → fail closed (zero rows), never all rows")
                    .isEmpty();
        }

        // cleanup as owner
        try (Connection owner = DriverManager.getConnection(
                url, nexuspayPg.getUsername(), nexuspayPg.getPassword());
             Statement st = owner.createStatement()) {
            st.execute("DELETE FROM ledger_accounts WHERE tenant_id IN ('rls_tA','rls_tB')");
        }
    }

    private static void seedAccount(Connection c, String id, String tenant) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ledger_accounts (id, name, type, currency, tenant_id) VALUES (?, ?, 'ASSET', 'USD', ?)")) {
            ps.setString(1, id);
            ps.setString(2, "RLS test " + tenant);
            ps.setString(3, tenant);
            ps.executeUpdate();
        }
    }

    /** Sets the tenant GUC at session scope (is_local=false) so it survives in autocommit. */
    private static void bindTenant(Connection c, String tenant) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.current_tenant_id', ?, false)")) {
            ps.setString(1, tenant);
            ps.executeQuery().close();
        }
    }

    private static List<String> visibleTestTenants(Connection c) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT DISTINCT tenant_id FROM ledger_accounts "
                             + "WHERE tenant_id IN ('rls_tA','rls_tB') ORDER BY tenant_id")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static boolean isSuperuser(Connection c) throws Exception {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT rolsuper FROM pg_roles WHERE rolname = current_user")) {
            return rs.next() && rs.getBoolean(1);
        }
    }
}
