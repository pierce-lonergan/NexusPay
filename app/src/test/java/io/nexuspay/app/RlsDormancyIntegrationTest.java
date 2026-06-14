package io.nexuspay.app;

import com.zaxxer.hikari.HikariDataSource;
import io.nexuspay.app.rls.RlsEnforcementConfig;
import io.nexuspay.app.rls.RoleRoutingDataSource;
import io.nexuspay.app.rls.SystemRoleAspect;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;

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
    }
}
