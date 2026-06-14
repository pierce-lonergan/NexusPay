package io.nexuspay.payment.adapter.out.compliance;

import io.nexuspay.payment.adapter.out.compliance.SanctionsListAdapter.OfacParseResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BLOCKER FIX 1/2 parser test. The real OFAC Consolidated Screening List CSV has a 29-column
 * header with NO {@code countries}/{@code country} column — the country is the trailing ISO-2
 * token of each {@code ;}-separated address (plus nationalities/citizenships/vessel_flag), and
 * the comprehensive-embargo set is scoped by the {@code programs} column. The fixture
 * ({@code /ofac/csl-sample.csv}) is a FAITHFUL excerpt of the real feed (real 29-col header +
 * real-style SDN rows for KP/IR/SY/CU/RU, a multi-address ';'-separated row, region/no-ISO rows,
 * and a non-embargo-program row that must NOT be embargoed).
 *
 * <p>This locks the contract that the prior fabricated 9-col {@code countries}-schema fixture
 * validated the WRONG thing: a parser keyed on a non-existent column.</p>
 */
class SanctionsListParserTest {

    private static String fixture() throws IOException {
        try (InputStream in = SanctionsListParserTest.class.getResourceAsStream("/ofac/csl-sample.csv")) {
            assertThat(in).as("csl-sample.csv fixture present").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void realHeaderDoesNotThrow_andHasNoCountriesColumn() throws IOException {
        String csv = fixture();
        // The real header has NO 'countries'/'country' column; the old parser threw on it.
        assertThat(csv.split("\\R", 2)[0].toLowerCase()).doesNotContain(",countries").doesNotContain(",country,");
        // Parsing the real header must succeed, not throw "missing country column".
        OfacParseResult result = SanctionsListAdapter.parseOfacCsv(csv);
        assertThat(result).isNotNull();
    }

    @Test
    void extractsComprehensiveEmbargoSet_fromAddressesAndPrograms_notACountriesColumn() throws IOException {
        OfacParseResult result = SanctionsListAdapter.parseOfacCsv(fixture());
        // KP/IR/SY/CU come from comprehensive-embargo programs confirmed by address ISO-2 tails;
        // RU comes from a RUSSIA-* program. All derived without any 'countries' column.
        assertThat(result.countries()).contains("KP", "IR", "SY", "CU", "RU");
    }

    @Test
    void addressTrailingIso2_isTheCountrySignal() throws IOException {
        // "Saemul 1-Dong Pyongchon District, Pyongyang, KP" → KP from the trailing token.
        // "..., Tehran, IR" → IR. "..., Havana, CU" → CU. These are the real encoding.
        OfacParseResult result = SanctionsListAdapter.parseOfacCsv(fixture());
        assertThat(result.countries()).contains("KP", "IR", "CU");
    }

    @Test
    void multiAddressRow_extractsEachTrailingIso2_butOnlyEmbargoesProgramScoped() throws IOException {
        // Row 12349: addresses = "..., Moscow, RU; ..., Mumbai, IN". Both ISO-2 tails are parsed,
        // but only RU enters the embargoed set (the RUSSIA-* program scopes it); IN does NOT,
        // because no comprehensive-embargo program names India — proving the program gate works
        // and we do NOT sanction every country that merely appears in an SDN address.
        OfacParseResult result = SanctionsListAdapter.parseOfacCsv(fixture());
        assertThat(result.countries()).contains("RU");
        assertThat(result.countries()).doesNotContain("IN");
    }

    @Test
    void nonComprehensiveEmbargoProgram_doesNotEmbargoTheAddressCountry() throws IOException {
        // Row 12353: program NPWMD (cross-cutting, not country-comprehensive), address "..., London, GB".
        // GB must NOT be embargoed — this is the core over-block hazard the BLOCKER fix closes.
        OfacParseResult result = SanctionsListAdapter.parseOfacCsv(fixture());
        assertThat(result.countries()).doesNotContain("GB");
    }

    @Test
    void nationalitiesAndVesselFlag_augmentGeography() throws IOException {
        // Row 12345 nationalities/citizenships = "Korea, North" (KP); row 12354 vessel_flag = "Iran" (IR).
        // These name-bearing fields resolve via CountryNameResolver and confirm KP/IR.
        OfacParseResult result = SanctionsListAdapter.parseOfacCsv(fixture());
        assertThat(result.countries()).contains("KP", "IR");
    }

    @Test
    void quotedCommaBearingName_doesNotShiftColumns() throws IOException {
        // Row 12352 NAME = "Reconnaissance Bureau, Second" (quoted, embedded comma), program DPRK3,
        // address "..., Pyongyang, KP". A naive raw-comma split would shift columns and lose KP.
        OfacParseResult result = SanctionsListAdapter.parseOfacCsv(fixture());
        assertThat(result.countries()).contains("KP");
    }

    @Test
    void regionLevelEntries_areTagged_notSilentlyDropped_norMisResolvedToUa() throws IOException {
        OfacParseResult result = SanctionsListAdapter.parseOfacCsv(fixture());
        // Crimea (12350) + so-called Donetsk republic (12351): no ISO-2 tail → tagged as regions,
        // not vanished (a silent drop = re-introduced fail-open for that region), and NOT mapped to UA.
        assertThat(result.regions()).contains("UA-43");   // Crimea
        assertThat(result.regions()).contains("UA-DPR");  // Donetsk
        assertThat(result.countries()).doesNotContain("UA");
    }

    @Test
    void curatedBaselineAlwaysPresent_soAParserMissNeverEmptiesTheSet() throws IOException {
        // Even if every row failed to parse, KP/IR/SY/CU must remain (curated baseline union).
        OfacParseResult result = SanctionsListAdapter.parseOfacCsv(fixture());
        assertThat(result.countries()).contains("KP", "IR", "SY", "CU");
        assertThat(result.countries().size()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void emptyBody_throws_soRefreshRoutesToFailClosed() {
        // An empty/blank body must throw → refreshSanctionsList catch → ofacAvailable=false
        // (health DOWN), never a silent empty success.
        assertThatThrownBy(() -> SanctionsListAdapter.parseOfacCsv(""))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> SanctionsListAdapter.parseOfacCsv(null))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void headerWithoutProgramsOrGeography_throws() {
        // A header lacking BOTH the programs column AND any geography column cannot drive the
        // program-scoped, address-ISO-2 derivation → must throw (not silently yield nothing).
        String noUsable = "_id,source,entity_number,type,name\nx,SDN,1,Entity,Foo\n";
        assertThatThrownBy(() -> SanctionsListAdapter.parseOfacCsv(noUsable))
                .isInstanceOf(RuntimeException.class);
    }

    // ---- low-level tokenizer sanity ----

    @Test
    void csvLineTokenizer_handlesQuotesEmbeddedCommasAndEscapedQuotes() {
        List<String> f = SanctionsListAdapter.parseCsvLine("a,\"b, c\",\"d\"\"e\",f");
        assertThat(f).containsExactly("a", "b, c", "d\"e", "f");
    }

    @Test
    void embeddedNewlineInsideQuotedAddress_doesNotShearTheRecord() throws IOException {
        // The real CSL embeds newlines inside long quoted address/remarks cells. A naive line split
        // would shear the record and shift the programs/addresses columns. Build a 2-row CSV whose
        // first data row has an address cell with an embedded newline; KP must still be extracted.
        String header = fixture().split("\\R", 2)[0];
        String row = "z1,SDN,99001,Entity,DPRK2,\"Some Co\",,\"Line one,\nstill same address, Pyongyang, KP\","
                + ",,,,,,,,,,,,Embedded newline addr,https://x,,,,,,,";
        OfacParseResult result = SanctionsListAdapter.parseOfacCsv(header + "\n" + row + "\n");
        assertThat(result.countries()).contains("KP");
    }

    // ---- CountryNameResolver direct coverage ----

    @Test
    void resolver_acceptsExistingIso2_andCuratedAliases() {
        assertThat(CountryNameResolver.toIso2("KP")).contains("KP");
        assertThat(CountryNameResolver.toIso2("kp")).contains("KP");
        assertThat(CountryNameResolver.toIso2("Korea, North")).contains("KP");
        assertThat(CountryNameResolver.toIso2("Iran, Islamic Republic of")).contains("IR");
        assertThat(CountryNameResolver.toIso2("Burma")).contains("MM");
        assertThat(CountryNameResolver.toIso2("Myanmar")).contains("MM");
        assertThat(CountryNameResolver.toIso2("Russian Federation")).contains("RU");
    }

    @Test
    void resolver_returnsEmptyForGarbage_andTagsRegions() {
        assertThat(CountryNameResolver.toIso2("Republic of Atlantis")).isEmpty();
        assertThat(CountryNameResolver.toIso2("")).isEmpty();
        assertThat(CountryNameResolver.toIso2(null)).isEmpty();
        assertThat(CountryNameResolver.toRegionTag("Crimea")).contains("UA-43");
        assertThat(CountryNameResolver.isRestrictedRegion("Luhansk")).isTrue();
        assertThat(CountryNameResolver.isRestrictedRegion("France")).isFalse();
    }
}
