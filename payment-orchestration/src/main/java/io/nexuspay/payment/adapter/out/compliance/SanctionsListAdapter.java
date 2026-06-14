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
import java.time.Duration;
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

    private final RestClient restClient;

    /**
     * OFAC Consolidated Screening List (CSL) CSV feed URL (configurable via
     * {@code nexuspay.fx.compliance.ofac-csl-url}; default is the live US Treasury feed). The real
     * feed is a 29-column entity export with NO {@code countries}/{@code country} column — country
     * is encoded as the trailing ISO-2 token of each {@code ;}-separated {@code addresses} entry
     * (plus {@code nationalities}/{@code citizenships}/{@code vessel_flag}), and the embargo regime
     * is named in {@code programs}. {@link #parseOfacCsv(String)} derives the comprehensive-embargo
     * ISO-2 set from those REAL columns (BLOCKER FIX 1). In production, parse the full SDN XML for
     * entity-level screening. Made configurable so unit tests can point at an unreachable host and
     * never hit the live feed (an absolute URI overrides any RestClient baseUrl).
     */
    private final String ofacCslUrl;
    private final Set<String> staticSanctionedCountries;
    private final Set<String> highRiskCountries;
    private final BigDecimal reportingThreshold;
    private final Duration maxStaleness;

    /** Live sanctions list — updated by the scheduled refresh. Thread-safe via ConcurrentHashMap.newKeySet(). */
    private final Set<String> liveSanctionedCountries = ConcurrentHashMap.newKeySet();

    /**
     * Coarse region restrictions (Crimea / DNR / LNR) parsed from the CSL that have no
     * ISO-2 country. Surfaced for health/audit; NOT folded into a blanket country block
     * (UA-bound traffic cannot be separated from Crimea-bound at country granularity).
     */
    private final Set<String> liveRestrictedRegions = ConcurrentHashMap.newKeySet();

    private volatile Instant lastRefreshed = Instant.EPOCH;
    private volatile boolean ofacAvailable = false;

    public SanctionsListAdapter(
            RestClient.Builder restClientBuilder,
            @Value("${nexuspay.fx.compliance.ofac-csl-url:https://data.trade.gov/downloadable_consolidated_screening_list/v1/consolidated.csv}") String ofacCslUrl,
            @Value("${nexuspay.fx.compliance.sanctioned-countries:KP,IR,SY,CU}") List<String> sanctionedCountries,
            @Value("${nexuspay.fx.compliance.high-risk-countries:AF,BY,MM,VE,ZW,LY,SO,YE,SD}") List<String> highRiskCountries,
            @Value("${nexuspay.fx.compliance.cross-border-amount-reporting-threshold:10000}") BigDecimal reportingThreshold,
            @Value("${nexuspay.fx.compliance.sanctions-max-staleness:PT48H}") Duration maxStaleness) {
        this.restClient = restClientBuilder.build();
        this.ofacCslUrl = ofacCslUrl;
        this.reportingThreshold = reportingThreshold;
        this.maxStaleness = maxStaleness;

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
            OfacParseResult parsed = fetchOfacSanctionedCountries();
            Set<String> updatedCountries = parsed.countries();
            if (!updatedCountries.isEmpty()) {
                // Merge with static list — static entries are always included as baseline
                updatedCountries.addAll(staticSanctionedCountries);
                liveSanctionedCountries.clear();
                liveSanctionedCountries.addAll(updatedCountries);
                liveRestrictedRegions.clear();
                liveRestrictedRegions.addAll(parsed.regions());
                lastRefreshed = Instant.now();
                ofacAvailable = true;
                LOG.info("Sanctions list refreshed from OFAC: {} country entries, {} region restrictions (last refresh: {})",
                        liveSanctionedCountries.size(), liveRestrictedRegions.size(), lastRefreshed);
            } else {
                // CRITICAL: the parse yielded NO sanctioned countries. This is the exact failure
                // mode B-026 fixed (a parser bug emptied the list and the screen silently degraded).
                // Do NOT mark OFAC available — leave the screen flagged unhealthy so the readiness
                // probe goes DOWN and the pod is pulled from rotation. We retain the previous list
                // (which still contains at least the static baseline) so the screen never opens fully.
                ofacAvailable = false;
                LOG.error("OFAC refresh returned an EMPTY country set — sanctions screen is DEGRADED. "
                        + "Retaining {} existing entries; readiness will report DOWN.",
                        liveSanctionedCountries.size());
            }
        } catch (Exception e) {
            // Feed unreachable / malformed body. Retain existing entries (static baseline at minimum)
            // but mark OFAC unavailable so health surfaces it. The read path still fails closed when
            // even the static baseline is gone (it never should be, but isScreeningAvailable() guards it).
            LOG.error("Failed to refresh sanctions list from OFAC: {}. Retaining {} existing entries; "
                    + "readiness will report DOWN.", e.getMessage(), liveSanctionedCountries.size());
            ofacAvailable = false;
        }
    }

    /** Result of parsing the CSL: comprehensive-embargo ISO-2 countries + coarse region tags. */
    record OfacParseResult(Set<String> countries, Set<String> regions) {
    }

    /**
     * Curated comprehensive-embargo ISO-2 baseline (BLOCKER FIX 1). The real OFAC Consolidated
     * Screening List has NO {@code countries}/{@code country} column; country is encoded as the
     * trailing ISO-2 token of each {@code ;}-separated {@code addresses} entry (plus
     * {@code nationalities}/{@code citizenships}/{@code vessel_flag}). Deriving "sanctioned
     * countries" from EVERY address ISO-2 would wrongly embargo every jurisdiction that merely
     * appears in some SDN address (e.g. an Iranian front company with a London PO box would
     * sanction GB). So the embargoed set is scoped to COUNTRY-LEVEL COMPREHENSIVE EMBARGO
     * programs only: we keep this curated ISO-2 baseline as the source of truth and use the feed
     * to CONFIRM/AUGMENT it — an address ISO-2 is admitted ONLY when the entity's {@code programs}
     * cell names a comprehensive-embargo program for that jurisdiction.
     */
    static final Set<String> COMPREHENSIVE_EMBARGO_ISO2 =
            Set.of("KP", "IR", "SY", "CU");

    /**
     * Maps a comprehensive-embargo PROGRAM keyword (as it appears in the feed's {@code programs}
     * cell, e.g. {@code DPRK2}, {@code IRAN-EO13902}, {@code SYRIA}, {@code CUBA}) to the ISO-2 it
     * embargoes. Matching is by prefix on the upper-cased program token, so versioned/EO-suffixed
     * variants (IRAN, IRAN-HR, IRAN-CON-ARMS-EO; DPRK, DPRK2, DPRK3; SYRIA, SYRIA-EO13894) all
     * resolve. BURMA/MM and VENEZUELA/VE are listed because policy treats them as country-scoped
     * programs the feed should be allowed to surface; they are NOT in the hard comprehensive
     * baseline (they are high-risk/sectoral), so they are only added when the feed confirms them.
     */
    private static final Map<String, String> EMBARGO_PROGRAM_PREFIX_TO_ISO2 = buildEmbargoProgramMap();

    private static Map<String, String> buildEmbargoProgramMap() {
        Map<String, String> m = new HashMap<>();
        m.put("CUBA", "CU");
        m.put("IRAN", "IR");
        m.put("IFSR", "IR");        // Iran Financial Sanctions Regulations
        m.put("SYRIA", "SY");
        m.put("DPRK", "KP");        // DPRK, DPRK2, DPRK3, DPRK4, DPRK-NKPR
        m.put("NKOREA", "KP");
        // NB: deliberately NOT mapping cross-cutting programs like NPWMD/WMD/IFCA — they appear on
        // entities from MANY countries and are not country-comprehensive embargoes; mapping them
        // would re-introduce the over-block hazard FIX 1 closes.
        m.put("BURMA", "MM");
        m.put("VENEZUELA", "VE");
        // Country-scoped sanctions REGIMES the feed should surface to AUGMENT the embargoed set
        // (not in the hard comprehensive baseline; admitted only when the feed names them). RU/BY
        // are EO-based, not "comprehensive", but policy here blocks them when the feed confirms a
        // RUSSIA-*/UKRAINE-EO-on-RU / BELARUS program. Tune per legal guidance.
        m.put("RUSSIA", "RU");
        m.put("BELARUS", "BY");
        return Map.copyOf(m);
    }

    /**
     * Fetches the comprehensive-embargo country set from the OFAC Consolidated Screening List.
     *
     * <p>The feed is the real 29-column CSL (no {@code countries} column). See
     * {@link #parseOfacCsv(String)} for the column-by-header-name + programs-scoped derivation.</p>
     */
    OfacParseResult fetchOfacSanctionedCountries() {
        String csv = restClient.get()
                .uri(ofacCslUrl)
                .retrieve()
                .body(String.class);

        return parseOfacCsv(csv);
    }

    /**
     * Parses the REAL CSL CSV body (BLOCKER FIX 1). Package-private + static so the parser is
     * unit-testable against a faithful fixture without touching the network.
     *
     * <p>The real header has NO {@code countries}/{@code country} column. Country is derived from:
     * <ul>
     *   <li>{@code addresses} — {@code ;}-separated; the country is the TRAILING comma-delimited
     *       token of each address (e.g. {@code "..., Pyongyang, KP"} → KP), kept only if it is a
     *       valid ISO-2 ({@link Locale#getISOCountries()});</li>
     *   <li>{@code nationalities} / {@code citizenships} / {@code vessel_flag} — name-or-code
     *       tokens, resolved via {@link CountryNameResolver}.</li>
     * </ul>
     * A derived ISO-2 is admitted to the EMBARGOED set ONLY when the entity's {@code programs}
     * cell names a comprehensive-embargo program for it (so we do not embargo every country that
     * merely appears in an SDN address). The curated {@link #COMPREHENSIVE_EMBARGO_ISO2} baseline
     * is always unioned in so a parser miss never empties the set; the feed confirms/augments it.</p>
     */
    static OfacParseResult parseOfacCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            throw new RuntimeException("Empty response from OFAC CSL endpoint");
        }

        List<String> lines = splitLines(csv);
        if (lines.isEmpty()) {
            throw new RuntimeException("OFAC CSL body had no lines");
        }

        // Locate the REAL columns by HEADER NAME (the feed has no 'countries' column).
        List<String> headers = parseCsvLine(lines.get(0));
        int programsIdx = headerIndex(headers, "programs");
        int addressesIdx = headerIndex(headers, "addresses");
        int nationalitiesIdx = headerIndex(headers, "nationalities");
        int citizenshipsIdx = headerIndex(headers, "citizenships");
        int vesselFlagIdx = headerIndex(headers, "vessel_flag");

        // The two columns the derivation truly needs are programs (for embargo scoping) and at
        // least one geography-bearing column (addresses, or a nationality/citizenship/flag).
        boolean hasGeoColumn = addressesIdx >= 0 || nationalitiesIdx >= 0
                || citizenshipsIdx >= 0 || vesselFlagIdx >= 0;
        if (programsIdx < 0 || !hasGeoColumn) {
            LOG.warn("OFAC CSV header missing required columns (programs={}, addresses={}, "
                            + "nationalities={}, citizenships={}, vessel_flag={}): {}",
                    programsIdx, addressesIdx, nationalitiesIdx, citizenshipsIdx, vesselFlagIdx, headers);
            throw new RuntimeException("OFAC CSL header missing programs and/or a geography column");
        }

        Set<String> countries = new HashSet<>();
        Set<String> regions = new HashSet<>();

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            try {
                List<String> fields = parseCsvLine(line);

                // Scan the entity's REAL geography columns (per BLOCKER FIX 1): the trailing ISO-2
                // of each ';'-separated address, unioned with nationalities/citizenships/vessel_flag.
                // Region-only addresses (Crimea/DNR/LNR, no ISO-2 tail) are captured as region tags.
                Set<String> entityIso2 = new HashSet<>();
                collectAddressIso2(field(fields, addressesIdx), entityIso2, regions);
                collectNamedIso2(field(fields, nationalitiesIdx), entityIso2);
                collectNamedIso2(field(fields, citizenshipsIdx), entityIso2);
                collectNamedIso2(field(fields, vesselFlagIdx), entityIso2);

                // Embargo jurisdictions named by this entity's PROGRAMS cell. Empty → the entity is
                // not on a comprehensive country embargo, so NONE of its geography ISO-2s are added
                // to the embargoed set (this is the gate that prevents sanctioning every country that
                // merely appears in an SDN address — the BLOCKER's core over-block hazard).
                Set<String> programIso2 = embargoIso2FromPrograms(field(fields, programsIdx));
                if (programIso2.isEmpty()) {
                    continue; // regions already captured above; contribute no country
                }

                // A comprehensive-embargo program (CUBA, IRAN, DPRK, SYRIA) is authoritative about
                // the embargoed jurisdiction, so add the program's ISO-2 directly (an entity can be
                // an overseas front: Iranian program, London address). ADDITIONALLY admit any of
                // the entity's actual geography ISO-2s that are themselves comprehensive-embargo
                // jurisdictions — catches a resident/national of an embargoed country whose specific
                // program token we did not enumerate.
                countries.addAll(programIso2);
                for (String iso : entityIso2) {
                    if (COMPREHENSIVE_EMBARGO_ISO2.contains(iso)) {
                        countries.add(iso);
                    }
                }
            } catch (RuntimeException e) {
                LOG.warn("Skipping malformed OFAC CSV line {}: {}", i, e.getMessage());
            }
        }

        // Union the curated comprehensive-embargo baseline so a parser miss never empties the set.
        countries.addAll(COMPREHENSIVE_EMBARGO_ISO2);

        LOG.debug("Parsed {} comprehensive-embargo countries + {} restricted regions from OFAC CSL",
                countries.size(), regions.size());
        return new OfacParseResult(countries, regions);
    }

    /** ISO-2 jurisdictions named by a {@code programs} cell (prefix match on each program token). */
    private static Set<String> embargoIso2FromPrograms(String programsCell) {
        Set<String> out = new HashSet<>();
        if (programsCell == null || programsCell.isBlank()) {
            return out;
        }
        // programs are ';'-separated (sometimes also '/'); normalize both.
        for (String raw : programsCell.split("[;/]")) {
            String prog = raw.trim().toUpperCase(Locale.ROOT);
            if (prog.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, String> e : EMBARGO_PROGRAM_PREFIX_TO_ISO2.entrySet()) {
                if (prog.startsWith(e.getKey())) {
                    out.add(e.getValue());
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Collect the trailing ISO-2 token of each {@code ;}-separated address (the country position
     * in the real feed). Region-only addresses (Crimea/Donetsk, no ISO-2 tail) are tagged into
     * {@code regions} so they are not silently dropped.
     */
    private static void collectAddressIso2(String addressesCell, Set<String> iso2, Set<String> regions) {
        if (addressesCell == null || addressesCell.isBlank()) {
            return;
        }
        for (String addr : addressesCell.split(";")) {
            String a = addr.trim();
            if (a.isEmpty()) {
                continue;
            }
            // Trailing comma-delimited token = country position.
            int comma = a.lastIndexOf(',');
            String tail = (comma >= 0 ? a.substring(comma + 1) : a).trim();
            String key = tail.toUpperCase(Locale.ROOT);
            if (tail.length() == 2 && ISO2.contains(key)) {
                iso2.add(key);
                continue;
            }
            // No ISO-2 tail — try to tag a known coarse region (Crimea/DNR/LNR) from any token.
            collectRegions(a, regions);
        }
    }

    /** Whether a token (address/region name) maps to a known coarse region tag; if so, record it. */
    private static void collectRegions(String text, Set<String> regions) {
        if (text == null || text.isBlank()) {
            return;
        }
        // Scan each comma-token; a region name (e.g. "Crimea", "Donetsk") can sit anywhere.
        for (String tok : text.split("[,;]")) {
            CountryNameResolver.toRegionTag(tok.trim()).ifPresent(regions::add);
        }
    }

    /**
     * Collect ISO-2 from a name-or-code-bearing field (nationalities/citizenships/vessel_flag),
     * which the feed populates with names ("Iran") or codes; resolved via {@link CountryNameResolver}.
     *
     * <p>These fields are {@code ;}-separated for multiple values, and a single value may itself
     * contain a comma ("Korea, North"). So we split on {@code ;} and try each token WHOLE first
     * (catches "Korea, North"); only if that fails do we comma-split the token (catches a feed that
     * used commas to separate multiple values).</p>
     */
    private static void collectNamedIso2(String cell, Set<String> iso2) {
        if (cell == null || cell.isBlank()) {
            return;
        }
        for (String semi : cell.split(";")) {
            String whole = semi.trim();
            if (whole.isEmpty()) {
                continue;
            }
            Optional<String> resolved = CountryNameResolver.toIso2(whole);
            if (resolved.isPresent()) {
                iso2.add(resolved.get());
                continue;
            }
            for (String comma : whole.split(",")) {
                CountryNameResolver.toIso2(comma.trim()).ifPresent(iso2::add);
            }
        }
    }

    /** Field accessor tolerant of a missing column index (-1) or a short row. */
    private static String field(List<String> fields, int idx) {
        if (idx < 0 || idx >= fields.size()) {
            return null;
        }
        return fields.get(idx);
    }

    /** Exact header-name match (case-insensitive, trimmed). Returns -1 when absent. */
    private static int headerIndex(List<String> headers, String name) {
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).trim().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    /** Valid ISO-2 codes for address-tail validation. */
    private static final Set<String> ISO2 = Set.copyOf(Arrays.asList(Locale.getISOCountries()));

    /**
     * Split a CSV body into logical RECORDS, tolerating \r\n / \n / \r AND quote-aware so a
     * newline INSIDE a {@code "}-quoted field (the real CSL does embed these in long
     * addresses/remarks cells) does NOT split a record. A naive line split would shear such a
     * record and shift every column after the quoted cell — silently corrupting the address /
     * programs parse. Embedded {@code ""} escaped quotes are respected so they don't toggle the
     * quote state.
     */
    private static List<String> splitLines(String csv) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    cur.append('"').append('"'); // escaped quote — keep both; tokenizer handles it
                    i++;
                } else {
                    inQuotes = !inQuotes;
                    cur.append(c);
                }
            } else if (!inQuotes && (c == '\n' || c == '\r')) {
                // record boundary (outside quotes); collapse \r\n into one
                if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
                    i++;
                }
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        // Trim trailing empty record(s) from a final newline.
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    /**
     * Minimal RFC-4180 line tokenizer: handles {@code "}-quoted fields, embedded commas,
     * and escaped {@code ""} quotes. Avoids a new CSV dependency.
     *
     * <p>Operates on a single logical RECORD as produced by {@link #splitLines(String)} (which is
     * quote-aware, so a record may legitimately contain embedded newlines inside a quoted cell —
     * those are preserved as field content here).</p>
     */
    static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"'); // escaped quote
                        i++;
                    } else {
                        inQuotes = false; // end of quoted field
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
        }
        fields.add(cur.toString());
        return fields;
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

    @Override
    public boolean isScreeningAvailable() {
        // FAIL CLOSED: screening is only "available" when there is a non-empty list to match
        // against. The constructor seeds the static baseline (KP/IR/SY/CU), so a healthy boot
        // BEFORE the first scheduled OFAC refresh still screens (no self-inflicted outage).
        //
        // Screening is UNAVAILABLE when:
        //   (a) the live list is empty (should never happen — would mean even the static
        //       baseline is gone — but if it does, we must NOT allow), OR
        //   (b) the list is stale (last successful refresh older than the staleness tolerance)
        //       AND a refresh has actually been attempted. We only consider staleness once a
        //       refresh has succeeded at least once (lastRefreshed != EPOCH); a never-refreshed
        //       boot is covered by the static baseline and is NOT treated as stale.
        if (liveSanctionedCountries.isEmpty()) {
            return false;
        }
        if (isStale()) {
            return false;
        }
        return true;
    }

    /**
     * True when a refresh has succeeded at least once AND that success is older than the
     * configured staleness tolerance. A never-refreshed adapter (lastRefreshed == EPOCH) is
     * NOT stale — it is operating on the static baseline by design until the first refresh.
     */
    public boolean isStale() {
        if (lastRefreshed.equals(Instant.EPOCH)) {
            return false;
        }
        return Duration.between(lastRefreshed, Instant.now()).compareTo(maxStaleness) > 0;
    }

    /** Number of sanctioned countries currently loaded (for health/audit — NOT the list itself). */
    public int getLiveCount() {
        return liveSanctionedCountries.size();
    }

    /** Coarse region restrictions (Crimea/DNR/LNR) currently loaded. */
    public Set<String> getRestrictedRegions() {
        return new HashSet<>(liveRestrictedRegions);
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
