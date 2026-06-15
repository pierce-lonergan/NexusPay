package io.nexuspay.payment.adapter.out.compliance;

import io.nexuspay.payment.adapter.out.compliance.SanctionsListAdapter.OfacParseResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adversarial-INPUT fuzz of the hardened OFAC CSL parser (in-gate, UNTAGGED).
 *
 * <p>Part of the simulation / red-team environment (see
 * {@code docs/simulation/README.md}). It targets {@code SanctionsListAdapter}'s
 * B-025-hardened {@code parseOfacCsv}/{@code parseCsvLine}, which is ALREADY
 * correct, so it PASSES on main and runs in the default {@code ./gradlew test}
 * gate. Deterministic, table-driven {@code @ParameterizedTest} — NOT a random
 * property engine (jqwik is not wired into the gate).</p>
 *
 * <p><strong>Fail-closed invariants under hostile input:</strong>
 * <ol>
 *   <li>The curated comprehensive-embargo baseline (KP/IR/SY/CU) is NEVER
 *       emptied — a parser miss can only ADD, never remove, so a sanctioned
 *       country can never be screened OUT by feeding garbage rows.</li>
 *   <li>A column-shifting / over-broad row never SANCTIONS a benign country
 *       (the program-gate prevents embargoing every country in an SDN address).</li>
 *   <li>The hard fail-closed cases (empty / null body / header with no
 *       programs+geography) THROW, routing the scheduled refresh to
 *       {@code ofacAvailable=false} (health DOWN) rather than a silent empty
 *       success.</li>
 *   <li>No hostile but well-formed row crashes the parse.</li>
 * </ol></p>
 */
@DisplayName("SanctionsListParser — adversarial OFAC CSV fuzz (fail-closed)")
class SanctionsListParserFuzzTest {

    private static final String REAL_HEADER =
            "_id,source,entity_number,type,programs,name,title,addresses,nationalities,"
                    + "citizenships,dates_of_birth,places_of_birth,call_sign,vessel_type,"
                    + "tonnage,gross_registered_tonnage,vessel_flag,vessel_owner,remarks,"
                    + "source_list_url,alt_names,start_date,end_date,standard_order,"
                    + "license_requirement,license_policy,information,federal_register_notice,ids";

    /** Build one CSL data row with the given programs + addresses cells (others blank). */
    private static String row(String entityNumber, String programs, String addresses) {
        return entityNumber + ",SDN," + entityNumber + ",Entity," + programs + ",Foo,,"
                + addresses + ",,,,,,,,,,,,,,,,,,,,,";
    }

    private static String csl(String... dataRows) {
        return REAL_HEADER + "\n" + String.join("\n", dataRows) + "\n";
    }

    // ---- (1) curated baseline can never be emptied by hostile rows ----

    static Stream<Arguments> hostileButParseableBodies() {
        return Stream.of(
                Arguments.of("all-garbage program/geography rows",
                        csl(row("1", "GARBAGE", "Nowhere, ZZ"),
                                row("2", "NPWMD", "London, GB"),
                                row("3", "", ""))),
                Arguments.of("quoted comma in name shifts nothing",
                        csl("9,SDN,9,Entity,DPRK3,\"Bureau, Second\",,\"Pyongyang, KP\",,,,,,,,,,,,,,,,,,,,,")),
                Arguments.of("embedded-quote escape in address",
                        csl("10,SDN,10,Entity,IRAN,Acme,,\"Tehran \"\"HQ\"\", IR\",,,,,,,,,,,,,,,,,,,,,")),
                Arguments.of("region-only address (no ISO-2 tail)",
                        csl(row("11", "", "Sevastopol, Crimea"))),
                Arguments.of("ragged short row is skipped, not fatal",
                        csl("12,SDN,12,Entity,CUBA,Havana Co", row("13", "SYRIA", "Damascus, SY")))
        );
    }

    @ParameterizedTest(name = "[{0}] baseline survives + no crash")
    @MethodSource("hostileButParseableBodies")
    void hostileBody_neverEmptiesCuratedBaseline_andDoesNotCrash(String name, String csv) {
        OfacParseResult result = assertNoCrash(name, csv);
        // The comprehensive-embargo baseline is ALWAYS present — a hostile feed
        // cannot screen a sanctioned country OUT.
        assertThat(result.countries())
                .as("[%s] curated baseline KP/IR/SY/CU is never lost", name)
                .contains("KP", "IR", "SY", "CU");
    }

    private OfacParseResult assertNoCrash(String name, String csv) {
        java.util.concurrent.atomic.AtomicReference<OfacParseResult> ref = new java.util.concurrent.atomic.AtomicReference<>();
        assertThatCode(() -> ref.set(SanctionsListAdapter.parseOfacCsv(csv)))
                .as("[%s] parse must not crash on hostile-but-parseable input", name)
                .doesNotThrowAnyException();
        assertThat(ref.get()).isNotNull();
        return ref.get();
    }

    // ---- (2) over-broad / column-shift rows never sanction a benign country ----

    @Test
    @DisplayName("a benign country in an SDN address is NOT embargoed (no over-block)")
    void benignCountryInAddress_isNotSanctioned() {
        // NPWMD is cross-cutting, not a country-comprehensive embargo: GB must NOT
        // be added even though it appears in the entity's address.
        OfacParseResult result = SanctionsListAdapter.parseOfacCsv(
                csl(row("100", "NPWMD", "London, GB")));
        assertThat(result.countries()).doesNotContain("GB");
    }

    @Test
    @DisplayName("multi-address row only embargoes the program-scoped jurisdiction")
    void multiAddressRow_onlyProgramScopedCountryIsEmbargoed() {
        // RUSSIA-* program + two addresses (RU and IN): RU is embargoed, IN is not.
        OfacParseResult result = SanctionsListAdapter.parseOfacCsv(
                csl(row("101", "RUSSIA-EO14024", "Moscow, RU; Mumbai, IN")));
        assertThat(result.countries()).contains("RU");
        assertThat(result.countries()).doesNotContain("IN");
    }

    // ---- (3) hard fail-closed cases MUST throw ----

    @ParameterizedTest(name = "fail-closed: empty/blank body throws → refresh routes DOWN")
    @ValueSource(strings = {"", "   ", "\n\n", "\t"})
    void emptyOrBlankBody_throws(String body) {
        assertThatThrownBy(() -> SanctionsListAdapter.parseOfacCsv(body))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("fail-closed: null body throws")
    void nullBody_throws() {
        assertThatThrownBy(() -> SanctionsListAdapter.parseOfacCsv(null))
                .isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest(name = "fail-closed: header missing programs+geography throws")
    @MethodSource("headersWithoutProgramsOrGeography")
    void headerWithoutProgramsOrGeography_throws(String name, String csv) {
        assertThatThrownBy(() -> SanctionsListAdapter.parseOfacCsv(csv))
                .as("[%s] must throw so the refresh fails closed, not yield an empty success", name)
                .isInstanceOf(RuntimeException.class);
    }

    static Stream<Arguments> headersWithoutProgramsOrGeography() {
        return Stream.of(
                Arguments.of("no programs, no geography",
                        "_id,source,entity_number,type,name\nx,SDN,1,Entity,Foo\n"),
                Arguments.of("geography but no programs column",
                        "_id,addresses,name\nx,\"Tehran, IR\",Foo\n"),
                Arguments.of("programs but no geography column",
                        "_id,programs,name\nx,IRAN,Foo\n")
        );
    }

    // ---- (4) low-level tokenizer never crashes / never shifts on hostile lines ----

    static Stream<Arguments> hostileTokenizerLines() {
        return Stream.of(
                Arguments.of("a,\"b, c\",\"d\"\"e\",f", List.of("a", "b, c", "d\"e", "f")),
                Arguments.of("", List.of("")),
                Arguments.of(",,", List.of("", "", "")),
                Arguments.of("\"unterminated", List.of("unterminated")),
                Arguments.of("plain,no,quotes", List.of("plain", "no", "quotes")),
                Arguments.of("\"\",\"\"", List.of("", ""))
        );
    }

    @ParameterizedTest(name = "tokenizer no-crash: \"{0}\"")
    @MethodSource("hostileTokenizerLines")
    void parseCsvLine_neverCrashes_andTokenizesAsExpected(String line, List<String> expected) {
        java.util.concurrent.atomic.AtomicReference<List<String>> ref = new java.util.concurrent.atomic.AtomicReference<>();
        assertThatCode(() -> ref.set(SanctionsListAdapter.parseCsvLine(line)))
                .doesNotThrowAnyException();
        assertThat(ref.get()).isEqualTo(expected);
    }

    @Test
    @DisplayName("embedded newline inside a quoted address does not shear the record")
    void embeddedNewlineInQuotedAddress_keepsRecordIntact() {
        String row = "z1,SDN,99001,Entity,DPRK2,\"Some Co\",,\"Line one,\nstill same, Pyongyang, KP\","
                + ",,,,,,,,,,,,Embedded newline addr,https://x,,,,,,,";
        OfacParseResult result = SanctionsListAdapter.parseOfacCsv(REAL_HEADER + "\n" + row + "\n");
        assertThat(result.countries()).contains("KP");
    }
}
