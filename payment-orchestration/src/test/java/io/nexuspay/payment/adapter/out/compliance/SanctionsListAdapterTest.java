package io.nexuspay.payment.adapter.out.compliance;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-026 adapter behavior that does NOT require the network: the static baseline screens at boot
 * (no self-inflicted outage), staleness, and the fail-closed isScreeningAvailable contract.
 */
class SanctionsListAdapterTest {

    private SanctionsListAdapter adapter() {
        // RestClient.builder() is never exercised here (no refresh() call), so a real builder is fine.
        return new SanctionsListAdapter(
                RestClient.builder(),
                List.of("KP", "IR", "SY", "CU"),
                List.of("VE", "AF"),
                new BigDecimal("10000"),
                Duration.ofHours(48));
    }

    @Test
    void atBoot_staticBaselineIsLoaded_andScreeningIsAvailable() {
        SanctionsListAdapter a = adapter();
        // Static baseline counts as a valid minimal screen → available even before first refresh.
        assertThat(a.isScreeningAvailable()).isTrue();
        assertThat(a.getLiveCount()).isEqualTo(4);
        assertThat(a.isStale()).isFalse(); // never-refreshed (EPOCH) is NOT treated as stale
        assertThat(a.isOfacAvailable()).isFalse(); // feed not yet contacted
    }

    @Test
    void sanctionedBaselineCountry_isBlocking_highRiskIsNot() {
        SanctionsListAdapter a = adapter();
        assertThat(a.checkCountryRestriction("KP")).hasValueSatisfying(r -> assertThat(r.isBlocking()).isTrue());
        assertThat(a.checkCountryRestriction("VE")).hasValueSatisfying(r -> assertThat(r.isBlocking()).isFalse());
        assertThat(a.checkCountryRestriction("US")).isEmpty();
    }

    @Test
    void refreshWithUnreachableFeed_marksOfacUnavailable_butRetainsBaseline() {
        // Point at an unroutable host so the fetch fails fast; the catch must flip ofacAvailable
        // false and retain the static baseline (screening still minimally available).
        SanctionsListAdapter a = new SanctionsListAdapter(
                RestClient.builder().baseUrl("http://localhost:1"),
                List.of("KP", "IR", "SY", "CU"),
                List.of("VE"),
                new BigDecimal("10000"),
                Duration.ofHours(48));

        a.refreshSanctionsList(); // network failure → catch branch

        assertThat(a.isOfacAvailable()).isFalse(); // health will report DOWN
        assertThat(a.getLiveCount()).isGreaterThanOrEqualTo(4); // baseline retained, never empty
    }
}
