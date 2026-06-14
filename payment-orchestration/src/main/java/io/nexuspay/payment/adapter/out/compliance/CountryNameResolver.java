package io.nexuspay.payment.adapter.out.compliance;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves a country NAME (as the OFAC Consolidated Screening List stores it, e.g.
 * "North Korea", "Korea, North", "Iran, Islamic Republic of") OR an ISO-2 code into a
 * canonical ISO 3166-1 alpha-2 code.
 *
 * <p>Why this exists (B-026): the previous parser filtered the CSL {@code countries}
 * column with a {@code length()==2} ISO-code test. The CSL stores full names, so EVERY
 * sanctioned country was discarded → empty live list → the screen silently fell back to
 * the 4-country static list forever. Dropping a sanctioned name re-introduces a fail-open
 * for that country, so an unresolvable token MUST be visible to the caller (logged at WARN)
 * rather than silently swallowed.</p>
 *
 * <p>Resolution order:
 * <ol>
 *   <li>input already a valid ISO-2 (length 2, present in {@link Locale#getISOCountries()})
 *       → return uppercased;</li>
 *   <li>curated alias map (case-insensitive, trimmed) — required because the CSL uses
 *       non-canonical names a {@code Locale} display-name lookup misses;</li>
 *   <li>reverse {@code Locale} display-name map (English) as a broad fallback;</li>
 *   <li>else {@link Optional#empty()} — caller logs and drops.</li>
 * </ol></p>
 *
 * <p>Region-level CSL entries that have NO ISO-2 country (Crimea, the so-called DNR/LNR /
 * Donetsk / Luhansk) are mapped to a {@code UA-*} tag in {@link #toRegionTag} so they are
 * NOT silently dropped. Region-level enforcement against a country-level destination is
 * coarse (we cannot separate Crimea-bound traffic from other UA traffic at country
 * granularity), so these tags are surfaced/screened separately and we deliberately do NOT
 * fold them into a blanket UA block.</p>
 *
 * @since B-026
 */
public final class CountryNameResolver {

    private CountryNameResolver() {
    }

    private static final Set<String> ISO2 = Set.copyOf(java.util.Arrays.asList(Locale.getISOCountries()));

    /** Curated name → ISO-2. Keys are upper-cased + trimmed for case-insensitive lookup. */
    private static final Map<String, String> ALIASES = buildAliases();

    /** Region name → coarse region tag (no ISO-2 country exists). Keys upper-cased + trimmed. */
    private static final Map<String, String> REGION_TAGS = buildRegionTags();

    /**
     * Reverse map of English display country names → ISO-2. Built once. This is the broad
     * fallback after the curated alias map; it catches canonical {@code Locale} names but
     * misses the CSL's idiosyncratic forms (hence the alias map).
     */
    private static final Map<String, String> DISPLAY_NAME_TO_ISO2 = buildDisplayNameMap();

    /**
     * Resolve a CSL country token (name or code) to a canonical ISO-2 code.
     *
     * @return the ISO-2 code, or empty if the token is not a resolvable country (caller
     *         should log at WARN — a dropped sanctioned country is a fail-open for that country)
     */
    public static Optional<String> toIso2(String nameOrCode) {
        if (nameOrCode == null) {
            return Optional.empty();
        }
        String raw = nameOrCode.trim();
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        String key = raw.toUpperCase(Locale.ROOT);

        // (1) already an ISO-2 code
        if (raw.length() == 2 && ISO2.contains(key)) {
            return Optional.of(key);
        }

        // (2) curated alias map
        String aliased = ALIASES.get(key);
        if (aliased != null) {
            return Optional.of(aliased);
        }

        // (3) reverse display-name map (English)
        String byDisplay = DISPLAY_NAME_TO_ISO2.get(key);
        if (byDisplay != null) {
            return Optional.of(byDisplay);
        }

        return Optional.empty();
    }

    /**
     * Resolve a region name (Crimea / DNR / LNR / Donetsk / Luhansk) to a coarse
     * region tag, for tokens that have no ISO-2 country. Returns empty for non-region
     * tokens (the caller should try {@link #toIso2} first).
     */
    public static Optional<String> toRegionTag(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String key = name.trim().toUpperCase(Locale.ROOT);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(REGION_TAGS.get(key));
    }

    /** Visible for tests / health: whether a token is a known coarse-region restriction. */
    public static boolean isRestrictedRegion(String name) {
        return toRegionTag(name).isPresent();
    }

    private static Map<String, String> buildAliases() {
        Map<String, String> m = new HashMap<>();
        // North Korea
        put(m, "KP", "North Korea", "Korea, North", "Korea, Democratic People's Republic of",
                "Democratic People's Republic of Korea", "DPRK", "N. Korea");
        // Iran
        put(m, "IR", "Iran", "Iran, Islamic Republic of", "Islamic Republic of Iran");
        // Syria
        put(m, "SY", "Syria", "Syrian Arab Republic");
        // Cuba
        put(m, "CU", "Cuba");
        // Burma / Myanmar
        put(m, "MM", "Burma", "Myanmar", "Burma (Myanmar)", "Myanmar (Burma)");
        // Venezuela
        put(m, "VE", "Venezuela", "Venezuela, Bolivarian Republic of", "Bolivarian Republic of Venezuela");
        // Russia
        put(m, "RU", "Russia", "Russian Federation");
        // Belarus
        put(m, "BY", "Belarus", "Republic of Belarus");
        // Libya
        put(m, "LY", "Libya");
        // Somalia
        put(m, "SO", "Somalia");
        // Yemen
        put(m, "YE", "Yemen");
        // Sudan vs South Sudan — disambiguate explicitly so neither hinges on Locale CLDR and
        // "South Sudan" is never mis-resolved to SD (or dropped). SS is a distinct jurisdiction.
        put(m, "SD", "Sudan", "Republic of the Sudan");
        put(m, "SS", "South Sudan", "Republic of South Sudan", "S. Sudan");
        // Afghanistan
        put(m, "AF", "Afghanistan", "Islamic Republic of Afghanistan");
        // Zimbabwe
        put(m, "ZW", "Zimbabwe");
        return Map.copyOf(m);
    }

    private static Map<String, String> buildRegionTags() {
        Map<String, String> m = new HashMap<>();
        // Crimea + the so-called DNR/LNR — no ISO-2 country; tag as UA-* coarse regions.
        put(m, "UA-43", "Crimea", "Crimea region", "Autonomous Republic of Crimea");
        put(m, "UA-DPR", "Donetsk", "Donetsk People's Republic", "DNR", "so-called Donetsk People's Republic");
        put(m, "UA-LPR", "Luhansk", "Luhansk People's Republic", "Lugansk", "LNR",
                "so-called Luhansk People's Republic");
        return Map.copyOf(m);
    }

    private static Map<String, String> buildDisplayNameMap() {
        Map<String, String> m = new HashMap<>();
        for (String cc : Locale.getISOCountries()) {
            String display = new Locale("", cc).getDisplayCountry(Locale.US);
            if (display != null && !display.isBlank()) {
                // First writer wins; ISO codes are unique enough that collisions are rare.
                m.putIfAbsent(display.trim().toUpperCase(Locale.ROOT), cc);
            }
        }
        return Map.copyOf(m);
    }

    private static void put(Map<String, String> m, String code, String... names) {
        for (String n : names) {
            m.put(n.trim().toUpperCase(Locale.ROOT), code);
        }
    }
}
