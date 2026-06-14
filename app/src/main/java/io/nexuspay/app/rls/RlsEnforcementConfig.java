package io.nexuspay.app.rls;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Wires B-002 RLS runtime enforcement — DORMANT unless
 * {@code nexuspay.multi-tenancy.rls.enforce=true}. When off, this whole class contributes ZERO
 * beans, so Spring Boot's {@code DataSourceAutoConfiguration} (@ConditionalOnMissingBean) supplies
 * the single owner datasource and {@code JpaBaseConfiguration} the default transaction manager —
 * exactly as before (the one runtime delta is the deletion of the old, RLS-inert
 * TenantAwareDataSource decorator).
 *
 * <p>When on: a {@link RoleRoutingDataSource} (@Primary) fronts two pools — {@code nexuspay_app}
 * (RLS-bound, default) and {@code nexuspay_system} (BYPASSRLS, for {@link io.nexuspay.common.rls.SystemTransactional}
 * jobs) — and {@link RlsRoutingTransactionManager} (@Primary) sets the tenant GUC per app
 * transaction. Flyway keeps running as the owner via {@code spring.flyway.user} (set in
 * {@code application-rls-enforce.yml}).</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "nexuspay.multi-tenancy.rls.enforce", havingValue = "true")
public class RlsEnforcementConfig {

    private static final Logger log = LoggerFactory.getLogger(RlsEnforcementConfig.class);

    @Bean
    @Primary
    public DataSource dataSource(Environment env) {
        String url = required(env, "spring.datasource.url");
        int poolSize = env.getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class, 8);

        HikariDataSource app = pool(url, required(env, "nexuspay.multi-tenancy.rls.app-username"),
                required(env, "nexuspay.multi-tenancy.rls.app-password"), "app", poolSize);
        HikariDataSource system = pool(url, required(env, "nexuspay.multi-tenancy.rls.system-username"),
                required(env, "nexuspay.multi-tenancy.rls.system-password"), "system", poolSize);

        RoleRoutingDataSource routing = new RoleRoutingDataSource();
        routing.setDefaultTargetDataSource(app);   // fail-closed default = APP (RLS-bound)
        routing.setTargetDataSources(Map.of(DbRole.APP, app, DbRole.SYSTEM, system));
        routing.afterPropertiesSet();
        log.warn("RLS ENFORCEMENT ENABLED: app traffic on '{}' (RLS-bound), cross-tenant jobs on '{}' (BYPASSRLS)",
                env.getProperty("nexuspay.multi-tenancy.rls.app-username"),
                env.getProperty("nexuspay.multi-tenancy.rls.system-username"));
        return routing;
    }

    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new RlsRoutingTransactionManager(emf);
    }

    private static HikariDataSource pool(String url, String user, String password, String name, int poolSize) {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(url);
        c.setUsername(user);
        c.setPassword(password);
        c.setMaximumPoolSize(poolSize);
        c.setPoolName("nexuspay-" + name);
        // Do NOT eagerly probe at construction: the app/system roles may not exist until Flyway
        // (running as owner) creates them, and the EMF must not fail-fast before that.
        c.setInitializationFailTimeout(-1);
        return new HikariDataSource(c);
    }

    private static String required(Environment env, String key) {
        String value = env.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("RLS enforce=true requires property: " + key);
        }
        return value;
    }
}
