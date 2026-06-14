package io.nexuspay.payment.adapter.out.compliance;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MUST_FIX 6: embargoed-country name resolution must NOT hinge on the JVM Locale / CLDR provider.
 *
 * <p>{@link CountryNameResolver#toIso2(String)} consults the curated ALIAS map (step 2) BEFORE the
 * {@code Locale} display-name fallback (step 3), so for any name that is an alias key the result is
 * deterministic regardless of the platform's CLDR data. These tests assert the comprehensive set
 * resolves from its bare canonical short names, and that they STILL resolve under a hostile default
 * Locale (CLDR display names differ by locale; the alias map must win).</p>
 */
class CountryNameResolverLocaleTest {

    @Test
    void curatedAliasMap_resolvesEmbargoedSet_fromBareNames() {
        assertThat(CountryNameResolver.toIso2("North Korea")).contains("KP");
        assertThat(CountryNameResolver.toIso2("Iran")).contains("IR");
        assertThat(CountryNameResolver.toIso2("Syria")).contains("SY");
        assertThat(CountryNameResolver.toIso2("Cuba")).contains("CU");
        assertThat(CountryNameResolver.toIso2("Russia")).contains("RU");
        assertThat(CountryNameResolver.toIso2("Belarus")).contains("BY");
        assertThat(CountryNameResolver.toIso2("Myanmar")).contains("MM");
        assertThat(CountryNameResolver.toIso2("Burma")).contains("MM");
        assertThat(CountryNameResolver.toIso2("Venezuela")).contains("VE");
    }

    @Test
    void sudanVsSouthSudan_areDisambiguated() {
        assertThat(CountryNameResolver.toIso2("Sudan")).contains("SD");
        assertThat(CountryNameResolver.toIso2("South Sudan")).contains("SS");
        assertThat(CountryNameResolver.toIso2("Republic of South Sudan")).contains("SS");
        // South Sudan must NOT collapse to Sudan.
        assertThat(CountryNameResolver.toIso2("South Sudan")).doesNotContain("SD");
    }

    @Test
    void embargoedSet_resolvesEvenUnderAHostileDefaultLocale() {
        // Flip the default Locale to one whose English-display fallback would NOT match these names,
        // proving the curated alias map (not the Locale CLDR fallback) is what resolves them.
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("ja", "JP"));
            assertThat(CountryNameResolver.toIso2("North Korea")).contains("KP");
            assertThat(CountryNameResolver.toIso2("Iran")).contains("IR");
            assertThat(CountryNameResolver.toIso2("Syria")).contains("SY");
            assertThat(CountryNameResolver.toIso2("Cuba")).contains("CU");
            assertThat(CountryNameResolver.toIso2("Russia")).contains("RU");
            assertThat(CountryNameResolver.toIso2("Belarus")).contains("BY");
            assertThat(CountryNameResolver.toIso2("Myanmar")).contains("MM");
            assertThat(CountryNameResolver.toIso2("Venezuela")).contains("VE");
            assertThat(CountryNameResolver.toIso2("North Korea")).contains("KP");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void iso2PassThrough_isAlsoLocaleIndependent() {
        assertThat(CountryNameResolver.toIso2("KP")).contains("KP");
        assertThat(CountryNameResolver.toIso2("ir")).contains("IR");
    }
}
