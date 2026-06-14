package io.nexuspay.payment.config;

import io.nexuspay.payment.adapter.out.compliance.SanctionsListAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness health indicator for the OFAC sanctions screen (B-026 + MUST_FIX 4/5).
 *
 * <p><b>Contributor key (FIX 4).</b> Spring derives a health-contributor key from the bean name
 * by stripping the {@code HealthIndicator} suffix. The class name {@code SanctionsReadiness…}
 * would yield the key {@code sanctionsReadiness}, but {@code application.yml} groups readiness
 * under {@code sanctions} — a silent key mismatch that left the readiness gating INERT (a degraded
 * screen never pulled the pod). The bean is therefore named {@code sanctionsHealthIndicator} via
 * {@code @Component("sanctionsHealthIndicator")} so the contributor key is exactly {@code sanctions},
 * matching the readiness group and the {@code management.health.sanctions.enabled} switch.</p>
 *
 * <p><b>Boot readiness (FIX 5).</b> UP/DOWN is aligned with the DECISION-path
 * {@link SanctionsListAdapter#isScreeningAvailable()} rather than {@code isOfacAvailable()}. A
 * fresh boot screens on the non-empty static baseline (lastRefreshed == EPOCH, not stale) and is
 * READY — there is no self-inflicted outage from boot until the 2am scheduled refresh. DOWN is
 * reserved for an ACTUAL degradation: the live list is empty, OR the list is stale (which only
 * triggers AFTER a prior successful refresh whose freshness then lapsed). {@code ofacAvailable}
 * and {@code stale} are still surfaced as details for operators, but {@code ofacAvailable==false}
 * alone (the normal pre-first-refresh boot state, or a failed refresh that retained the baseline)
 * does NOT by itself fail readiness.</p>
 *
 * <p>Details deliberately exclude the country list itself (info leak / B-028): only counts,
 * the availability/stale booleans, and the last-refresh timestamp are surfaced.</p>
 *
 * <p>Default-on; disable with {@code management.health.sanctions.enabled=false}. NOTE: disabling
 * the HEALTH indicator does NOT weaken the decision-path fail-closed — that is enforced
 * independently in {@code CrossBorderComplianceService} via {@code isScreeningAvailable()}.</p>
 */
@Component("sanctionsHealthIndicator")
@ConditionalOnEnabledHealthIndicator("sanctions")
public class SanctionsReadinessHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(SanctionsReadinessHealthIndicator.class);

    private final SanctionsListAdapter sanctions;

    public SanctionsReadinessHealthIndicator(SanctionsListAdapter sanctions) {
        this.sanctions = sanctions;
    }

    @Override
    public Health health() {
        // FIX 5: readiness tracks whether the screen can actually run (isScreeningAvailable),
        // NOT whether the OFAC feed has been contacted yet. Boot-on-static-baseline → UP.
        boolean screeningAvailable = sanctions.isScreeningAvailable();
        boolean ofacAvailable = sanctions.isOfacAvailable();
        boolean stale = sanctions.isStale();
        int liveCount = sanctions.getLiveCount();
        boolean empty = liveCount == 0;

        Health.Builder builder = screeningAvailable ? Health.up() : Health.down();

        if (!screeningAvailable) {
            // Actual degradation: empty list, or stale-after-a-prior-success.
            log.warn("Sanctions readiness DOWN: screeningAvailable=false (empty={}, stale={}, "
                    + "liveCount={}, ofacAvailable={})", empty, stale, liveCount, ofacAvailable);
        }

        return builder
                .withDetail("screeningAvailable", screeningAvailable)
                .withDetail("ofacAvailable", ofacAvailable)
                .withDetail("stale", stale)
                .withDetail("liveCount", liveCount)
                .withDetail("regionRestrictionCount", sanctions.getRestrictedRegions().size())
                .withDetail("lastRefreshed", sanctions.getLastRefreshed().toString())
                .build(); // NB: never include the country list (info leak)
    }
}
