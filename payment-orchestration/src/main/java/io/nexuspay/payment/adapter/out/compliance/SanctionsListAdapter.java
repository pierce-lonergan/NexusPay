package io.nexuspay.payment.adapter.out.compliance;

import io.nexuspay.payment.application.port.fx.CrossBorderCompliancePort;
import io.nexuspay.payment.domain.fx.CountryRestriction;
import io.nexuspay.payment.domain.fx.CrossBorderRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compliance adapter with automated sanctions list updates.
 * <p>
 * Sources:
 * <ul>
 *   <li>Primary: OFAC SDN (Specially Designated Nationals) country list from US Treasury</li>
 *   <li>Fallback: static configuration from application properties</li>
 * </ul>
 * <p>
 * The adapter auto-refreshes the sanctions list on a configurable schedule (default: daily).
 * If the OFAC feed is unreachable, it falls back to the statically configured list to ensure
 * the system never operates without sanctions screening.
 * <p>
 * High-risk countries are also auto-refreshable, with a configurable static list as baseline.
 * <p>
 * Resolves GAP-045: sanctions list is now auto-updated instead of purely static.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
@Component
public class SanctionsListAdapter implements CrossBorderCompliancePort {

    private static final Logger LOG = LoggerFactory.getLogger(SanctionsListAdapter.class);

    /**
     * OFAC Consolidated Sanctions List — country-level program data.
     * The CSL CSV endpoint provides sanctioned entity data including country codes.
     * In production, parse the full SDN XML for entity-level screening.
     */
    private static final String OFAC_CSL_URL =
            "https://data.trade.gov/downloadable_consolidated_screening_list/v1/consolidated.csv";

    private final RestClient restClient;
    private final Set<String> staticSanctionedCountries;
    private final Set<String> highRiskCountries;
    private final BigDecimal reportingThreshold;

    /** Live sanctions list — updated by the scheduled refresh. Thread-safe via ConcurrentHashMap.newKeySet(). */
    private final Set<String> liveSanctionedCountries = ConcurrentHashMap.newKeySet();

    private volatile Instant lastRefreshed = Instant.EPOCH;
    private volatile boolean ofacAvailable = false;

    public SanctionsListAdapter(
            RestClient.Builder restClientBuilder,
            @Value("${nexuspay.fx.compliance.sanctioned-countries:KP,IR,SY,CU}") List<String> sanctionedCountries,
            @Value("${nexuspay.fx.compliance.high-risk-countries:AF,BY,MM,VE,ZW,LY,SO,YE,SD}") List<String> highRiskCountries,
            @Value("${nexuspay.fx.compliance.cross-border-amount-reporting-threshold:10000}") BigDecimal reportingThreshold) {
        this.restClient = restClientBuilder.build();
        this.reportingThreshold = reportingThreshold;

        // Initialize static fallback lists
        this.staticSanctionedCountries = new HashSet<>();
        for (String code : sanctionedCountries) {
            this.staticSanctionedCountries.add(code.trim().toUpperCase());
        }

        this.highRiskCountries = new HashSet<>();
        for (String code : highRiskCountries) {
            this.highRiskCountries.add(code.trim().toUpperCase());
        }

        // Start with static list
        this.liveSanctionedCountries.addAll(this.staticSanctionedCountries);
        LOG.info("Initialized sanctions list with {} static entries, {} high-risk countries",
                this.liveSanctionedCountries.size(), this.highRiskCountries.size());
    }

    /**
     * Scheduled refresh of the sanctions list from OFAC.
     * Runs daily by default (configurable via cron expression).
     * On failure, retains the previous list and logs a warning.
     */
    @Scheduled(cron = "${nexuspay.fx.compliance.sanctions-refresh-cron:0 0 2 * * *}")
    public void refreshSanctionsList() {
        LOG.info("Starting scheduled sanctions list refresh...");
        try {
            Set<String> updatedCountries = fetchOfacSanctionedCountries();
            if (!updatedCountries.isEmpty()) {
                // Merge with static list — static entries are always included as baseline
                updatedCountries.addAll(staticSanctionedCountries);
                liveSanctionedCountries.clear();
                liveSanctionedCountries.addAll(updatedCountries);
                lastRefreshed = Instant.now();
                ofacAvailable = true;
                LOG.info("Sanctions list refreshed from OFAC: {} total entries (last refresh: {})",
                        liveSanctionedCountries.size(), lastRefreshed);
            } else {
                LOG.warn("OFAC returned empty country set, retaining current list of {} entries",
                        liveSanctionedCountries.size());
            }
        } catch (Exception e) {
            LOG.warn("Failed to refresh sanctions list from OFAC: {}. Retaining {} existing entries.",
                    e.getMessage(), liveSanctionedCountries.size());
            ofacAvailable = false;
        }
    }

    /**
     * Fetches sanctioned country codes from the OFAC Consolidated Screening List.
     * Parses the CSV to extract unique country codes from sanctioned entities.
     */
    private Set<String> fetchOfacSanctionedCountries() {
        String csv = restClient.get()
                .uri(OFAC_CSL_URL)
                .retrieve()
                .body(String.class);

        if (csv == null || csv.isEmpty()) {
            throw new RuntimeException("Empty response from OFAC CSL endpoint");
        }

        Set<String> countries = new HashSet<>();
        String[] lines = csv.split("\n");
        boolean headerSkipped = false;
        int countryColIndex = -1;

        for (String line : lines) {
            if (!headerSkipped) {
                // Detect the country column from the header
                String[] headers = line.split(",");
                for (int i = 0; i < headers.length; i++) {
                    String header = headers[i].trim().replace("\"", "").toLowerCase();
                    if (header.contains("country") || header.contains("addresses_country")) {
                        countryColIndex = i;
                        break;
                    }
                }
                headerSkipped = true;
                if (countryColIndex == -1) {
                    LOG.warn("Could not find country column in OFAC CSV header, using index 5 as fallback");
                    countryColIndex = 5;
                }
                continue;
            }

            try {
                String[] parts = line.split(",");
                if (parts.length > countryColIndex) {
                    String countryVal = parts[countryColIndex].trim().replace("\"", "").toUpperCase();
                    // Only accept ISO 3166-1 alpha-2 codes (2 chars)
                    if (countryVal.length() == 2 && countryVal.matches("[A-Z]{2}")) {
                        countries.add(countryVal);
                    }
                }
            } catch (Exception e) {
                LOG.trace("Skipping malformed OFAC CSV line");
            }
        }

        LOG.debug("Parsed {} unique country codes from OFAC CSL", countries.size());
        return countries;
    }

    @Override
    public Optional<CountryRestriction> checkCountryRestriction(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            return Optional.empty();
        }

        String normalized = countryCode.trim().toUpperCase();

        if (liveSanctionedCountries.contains(normalized)) {
            return Optional.of(new CountryRestriction(
                    normalized,
                    CountryRestriction.RestrictionType.SANCTIONED,
                    "Country is subject to comprehensive sanctions" +
                            (ofacAvailable ? " (OFAC-verified)" : " (static list)")
            ));
        }

        if (highRiskCountries.contains(normalized)) {
            return Optional.of(new CountryRestriction(
                    normalized,
                    CountryRestriction.RestrictionType.HIGH_RISK,
                    "Country is classified as high-risk for financial transactions"
            ));
        }

        return Optional.empty();
    }

    @Override
    public Optional<CrossBorderRule> getRule(String sourceCountry, String destinationCountry) {
        if (sourceCountry != null && destinationCountry != null
                && !sourceCountry.equalsIgnoreCase(destinationCountry)) {
            return Optional.of(new CrossBorderRule(
                    sourceCountry, destinationCountry,
                    reportingThreshold, "USD",
                    false, false
            ));
        }
        return Optional.empty();
    }

    @Override
    public List<String> getSanctionedCountries() {
        return new ArrayList<>(liveSanctionedCountries);
    }

    @Override
    public boolean requiresReporting(String sourceCountry, String destinationCountry,
                                     BigDecimal amount, String currency) {
        if (sourceCountry == null || destinationCountry == null) return false;
        if (sourceCountry.equalsIgnoreCase(destinationCountry)) return false;
        return amount.compareTo(reportingThreshold) >= 0;
    }

    /** Returns the timestamp of the last successful OFAC refresh. */
    public Instant getLastRefreshed() {
        return lastRefreshed;
    }

    /** Returns whether the OFAC feed was reachable on the last attempt. */
    public boolean isOfacAvailable() {
        return ofacAvailable;
    }
}
