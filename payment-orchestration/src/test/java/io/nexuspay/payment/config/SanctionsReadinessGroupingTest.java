package io.nexuspay.payment.config;

import io.nexuspay.payment.adapter.out.compliance.SanctionsListAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MUST_FIX 4/5 regression-lock for the readiness GROUPING wiring.
 *
 * <p>FIX 4: the health-contributor key must be exactly {@code sanctions} (Spring derives it from
 * the bean name {@code sanctionsHealthIndicator}), so the {@code readiness} group's
 * {@code include: sanctions} actually picks it up — the silent {@code sanctionsReadiness}-vs-
 * {@code sanctions} mismatch previously left readiness gating inert.</p>
 *
 * <p>FIX 5: a freshly-booted context (no OFAC refresh yet) screens on the static baseline and
 * reports the {@code sanctions} contributor UP, so the readiness group is UP from boot — no
 * self-inflicted outage until 2am. A degraded adapter flips the SAME contributor (and therefore
 * the readiness group) DOWN.</p>
 *
 * <p>Uses {@link ApplicationContextRunner} with the real actuator health auto-configuration and
 * the same {@code management.endpoint.health.group.readiness.include=sanctions} mapping as
 * {@code application.yml}; no Docker / DB required.</p>
 */
class SanctionsReadinessGroupingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HealthEndpointAutoConfiguration.class))
            .withPropertyValues(
                    "management.endpoint.health.group.readiness.include=sanctions",
                    "management.health.sanctions.enabled=true",
                    "management.endpoint.health.show-details=always");

    @Configuration
    static class FreshBootConfig {
        /** Real adapter, never refreshed → static baseline → screening available (FIX 5). */
        @Bean
        SanctionsListAdapter sanctionsListAdapter() {
            return new SanctionsListAdapter(
                    RestClient.builder(),
                    "http://localhost:1/ofac.csv", // never fetched (no refresh in this test)
                    List.of("KP", "IR", "SY", "CU"),
                    List.of("VE"),
                    new BigDecimal("10000"),
                    Duration.ofHours(48));
        }

        @Bean
        SanctionsReadinessHealthIndicator sanctionsHealthIndicator(SanctionsListAdapter a) {
            return new SanctionsReadinessHealthIndicator(a);
        }
    }

    @Test
    void freshBoot_readinessGroupIsUp_andContributorKeyIsSanctions() {
        runner.withUserConfiguration(FreshBootConfig.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            HealthEndpoint endpoint = ctx.getBean(HealthEndpoint.class);

            // FIX 4: the contributor key is 'sanctions' — resolvable by that exact name.
            HealthComponent contributor = endpoint.healthForPath("sanctions");
            assertThat(contributor).as("'sanctions' contributor must resolve (key not 'sanctionsReadiness')")
                    .isNotNull();
            assertThat(contributor.getStatus()).isEqualTo(Status.UP); // FIX 5: boot READY

            // FIX 4 + 5: the readiness GROUP resolves and is UP from boot.
            HealthComponent readiness = endpoint.healthForPath("readiness");
            assertThat(readiness).as("readiness group must resolve").isNotNull();
            assertThat(readiness.getStatus()).isEqualTo(Status.UP);
            // The group must actually contain the sanctions contributor (proves the include matched).
            assertThat(((CompositeHealth) readiness).getComponents()).containsKey("sanctions");
        });
    }

    @Configuration
    static class DegradedConfig {
        /**
         * Adapter whose live list is empty → isScreeningAvailable()=false → DOWN. We force the
         * empty state by constructing with an empty baseline (no static countries), which makes
         * the live list empty and screening unavailable.
         */
        @Bean
        SanctionsListAdapter sanctionsListAdapter() {
            return new SanctionsListAdapter(
                    RestClient.builder(),
                    "http://localhost:1/ofac.csv", // never fetched (no refresh in this test)
                    List.of(),            // empty baseline → empty live list → screening unavailable
                    List.of("VE"),
                    new BigDecimal("10000"),
                    Duration.ofHours(48));
        }

        @Bean
        SanctionsReadinessHealthIndicator sanctionsHealthIndicator(SanctionsListAdapter a) {
            return new SanctionsReadinessHealthIndicator(a);
        }
    }

    @Test
    void degradedSanctions_pullsReadinessGroupDown() {
        runner.withUserConfiguration(DegradedConfig.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            HealthEndpoint endpoint = ctx.getBean(HealthEndpoint.class);

            assertThat(endpoint.healthForPath("sanctions").getStatus()).isEqualTo(Status.DOWN);
            // Regression-lock: a DOWN sanctions contributor pulls the readiness group DOWN.
            assertThat(endpoint.healthForPath("readiness").getStatus()).isEqualTo(Status.DOWN);
        });
    }
}
