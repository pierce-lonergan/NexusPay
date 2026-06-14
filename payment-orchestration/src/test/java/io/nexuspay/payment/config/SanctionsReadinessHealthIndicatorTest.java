package io.nexuspay.payment.config;

import io.nexuspay.payment.adapter.out.compliance.SanctionsListAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B-026 + MUST_FIX 5 readiness health. The indicator's UP/DOWN now tracks
 * {@link SanctionsListAdapter#isScreeningAvailable()} so a fresh boot on the static baseline is
 * READY (FIX 5: no self-inflicted boot outage), while an actual degradation (empty / stale-after-
 * a-prior-success) reports DOWN so the pod is pulled from the readiness rotation. Details never
 * leak the country list.
 */
class SanctionsReadinessHealthIndicatorTest {

    /** Mock adapter where screening availability is driven explicitly. */
    private SanctionsListAdapter adapter(boolean screeningAvailable, boolean ofacAvailable,
                                         boolean stale, int liveCount) {
        SanctionsListAdapter a = mock(SanctionsListAdapter.class);
        when(a.isScreeningAvailable()).thenReturn(screeningAvailable);
        lenient().when(a.isOfacAvailable()).thenReturn(ofacAvailable);
        lenient().when(a.isStale()).thenReturn(stale);
        lenient().when(a.getLiveCount()).thenReturn(liveCount);
        lenient().when(a.getRestrictedRegions()).thenReturn(Set.of("UA-43"));
        lenient().when(a.getLastRefreshed()).thenReturn(Instant.parse("2026-06-01T00:00:00Z"));
        return a;
    }

    @Test
    void freshBoot_staticBaseline_ofacNotYetContacted_isUp() {
        // FIX 5: screeningAvailable=true (baseline loaded), ofacAvailable=false (no refresh yet),
        // never-refreshed (not stale). Must be UP — a fresh pod is READY, not held out until 2am.
        Health h = new SanctionsReadinessHealthIndicator(
                adapter(true, false, false, 4)).health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("ofacAvailable", false);
    }

    @Test
    void postSuccessStaleDegradation_isDown() {
        // Stale only triggers after a prior success; isScreeningAvailable() then returns false.
        Health h = new SanctionsReadinessHealthIndicator(
                adapter(false, true, true, 30)).health();
        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void emptyLiveList_isDown() {
        Health h = new SanctionsReadinessHealthIndicator(
                adapter(false, true, false, 0)).health();
        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void refreshedNonEmptyFresh_isUp() {
        Health h = new SanctionsReadinessHealthIndicator(
                adapter(true, true, false, 30)).health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsKeys("screeningAvailable", "ofacAvailable", "stale",
                "liveCount", "lastRefreshed");
    }

    @Test
    void details_neverContainTheCountryList() {
        Health h = new SanctionsReadinessHealthIndicator(
                adapter(true, true, false, 30)).health();
        assertThat(h.getDetails().get("liveCount")).isInstanceOf(Integer.class);
        assertThat(h.getDetails().values()).noneMatch(v -> v instanceof java.util.Collection
                || (v instanceof String s && s.matches(".*\\b(KP|IR|SY|CU)\\b.*")));
    }

    @Test
    void freshlyBootedRealAdapter_noRefresh_isUp_thenEmptyish_neverPossibleBaselineGuards() {
        // Integration-flavored: a REAL adapter freshly constructed (no refresh) must be UP, proving
        // FIX 5 end-to-end without mocking isScreeningAvailable.
        SanctionsListAdapter real = new SanctionsListAdapter(
                RestClient.builder(),
                List.of("KP", "IR", "SY", "CU"),
                List.of("VE"),
                new BigDecimal("10000"),
                Duration.ofHours(48));
        Health h = new SanctionsReadinessHealthIndicator(real).health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);                 // boot READY
        assertThat(h.getDetails()).containsEntry("ofacAvailable", false); // feed not contacted
    }

    @Test
    void realAdapter_afterFailedRefresh_retainsBaseline_stillUp() {
        // A failed refresh (unreachable feed) flips ofacAvailable=false but retains the baseline →
        // screening still available → readiness stays UP (we do NOT shed traffic just because the
        // feed was briefly unreachable but the baseline screen is intact and fresh).
        SanctionsListAdapter real = new SanctionsListAdapter(
                RestClient.builder().baseUrl("http://localhost:1"),
                List.of("KP", "IR", "SY", "CU"),
                List.of("VE"),
                new BigDecimal("10000"),
                Duration.ofHours(48));
        real.refreshSanctionsList(); // network failure path
        Health h = new SanctionsReadinessHealthIndicator(real).health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("ofacAvailable", false);
    }
}
