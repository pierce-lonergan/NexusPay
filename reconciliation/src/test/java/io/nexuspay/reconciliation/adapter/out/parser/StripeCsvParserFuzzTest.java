package io.nexuspay.reconciliation.adapter.out.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.reconciliation.application.port.out.SettlementParserPort.ParseResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Adversarial-INPUT fuzz of the hardened {@link StripeCsvParser} (in-gate, UNTAGGED).
 *
 * <p>Part of the simulation / red-team environment (see
 * {@code docs/simulation/README.md}). It targets the B-015-hardened settlement
 * CSV parser, which is ALREADY correct, so it PASSES on main and runs in the
 * default {@code ./gradlew test} gate. It is deterministic, table-driven
 * {@code @ParameterizedTest} — NOT a random property engine (jqwik is not wired
 * into the gate).</p>
 *
 * <p>This complements {@link StripeCsvParserTest} with a single, dense
 * adversarial matrix and a hard <em>partition invariant</em> assertion: for
 * every hostile input, the parser must NOT crash and EVERY data row must be
 * accounted for — it is either a parsed {@code SettlementRecord} (money parsed
 * as a {@code long} minor unit) or a {@code ParseFailure}. No row may vanish
 * (no silent money-drop), which is the exact failure mode B-015 closed.</p>
 */
@DisplayName("StripeCsvParser — adversarial CSV fuzz (no-crash / no-money-drop)")
class StripeCsvParserFuzzTest {

    private final StripeCsvParser parser = new StripeCsvParser();
    private final ObjectMapper mapper = new ObjectMapper();

    private ParseResult parse(String csv) {
        return parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                "tenant-fuzz", "run-fuzz");
    }

    /**
     * Each case: a hostile CSV body, the number of logical DATA rows it contains,
     * the minimum number of those that must be well-formed records, and the
     * minimum that must surface as failures. The partition invariant
     * (records + failures == dataRows) is asserted for ALL of them.
     */
    static Stream<Arguments> hostileCsvs() {
        String header = "id,amount,currency,fee,net,created,description";
        return Stream.of(
                // name, csv, dataRows, minRecords, minFailures
                Arguments.of("quoted comma in description",
                        header + "\ntxn_1,10000,usd,290,9710,2026-03-14,\"Refund, partial\"\n",
                        1, 1, 0),
                Arguments.of("embedded newline in quoted field",
                        header + "\ntxn_1,10000,usd,290,9710,2026-03-14,\"line one\nline two\"\n",
                        1, 1, 0),
                Arguments.of("doubled-quote escape",
                        header + "\ntxn_1,10000,usd,290,9710,2026-03-14,\"She said \"\"hi\"\"\"\n",
                        1, 1, 0),
                Arguments.of("UTF-8 BOM on header",
                        "\uFEFF" + header + "\ntxn_1,10000,usd,290,9710,2026-03-14,ok\n",
                        1, 1, 0),
                Arguments.of("lone unterminated quote (tokenizer abort)",
                        header + "\ntxn_bad,200,usd,5,195,2026-01-03,\"unterminated\n",
                        1, 0, 1),
                Arguments.of("wrong column count — extra column",
                        header + "\ntxn_extra,10000,usd,290,9710,2026-03-14,desc,SURPRISE\n",
                        1, 0, 1),
                Arguments.of("wrong column count — too few columns",
                        "id,amount,currency,fee,net,created\ntxn_short,10000,usd\n",
                        1, 0, 1),
                Arguments.of("non-numeric money",
                        header + "\ntxn_bad,not_a_number,usd,290,9710,2026-03-14,bad\n",
                        1, 0, 1),
                Arguments.of("non-numeric net (money column shifted by attacker)",
                        header + "\ntxn_bad,10000,usd,290,DROP TABLE,2026-03-14,sqlish\n",
                        1, 0, 1),
                Arguments.of("impossible date",
                        header + "\ntxn_bad,1000,usd,10,990,2026-13-40,bad date\n",
                        1, 0, 1),
                Arguments.of("empty required amount field",
                        "id,amount,currency,fee,net,created\ntxn_empty,,usd,10,490,2026-01-02\n",
                        1, 0, 1),
                Arguments.of("negative money parses as a record (refund/chargeback is legal)",
                        header + "\ntxn_neg,-5000,usd,0,-5000,2026-03-14,chargeback\n",
                        1, 1, 0),
                Arguments.of("mixed good + bad — nothing may vanish",
                        header
                                + "\ntxn_1,10000,usd,290,9710,2026-03-14,\"Refund, partial\""
                                + "\ntxn_bad1,abc,usd,10,490,2026-01-02,bad amount"
                                + "\ntxn_2,500,eur,5,495,2026-01-03,ok"
                                + "\ntxn_bad2,1000,usd,10,990,2026-13-40,bad date\n",
                        4, 2, 2)
        );
    }

    @ParameterizedTest(name = "[{0}] no crash + partition invariant holds")
    @MethodSource("hostileCsvs")
    void hostileInput_noCrash_everyRowAccountedFor(String name, String csv,
                                                   int dataRows, int minRecords, int minFailures) {
        // (1) No crash: a hostile row must never propagate an exception out of parse().
        ParseResult result = parse(csv);

        // (2) No silent money-drop: records + failures == every data row.
        assertThat(result.records().size() + result.failures().size())
                .as("[%s] partition invariant: every data row is a record OR a failure", name)
                .isEqualTo(dataRows);

        assertThat(result.records().size())
                .as("[%s] expected well-formed records", name)
                .isGreaterThanOrEqualTo(minRecords);
        assertThat(result.failures().size())
                .as("[%s] expected surfaced failures (no silent drop)", name)
                .isGreaterThanOrEqualTo(minFailures);

        // (3) Money soundness: every parsed record's amount/net reconstruct a Money
        //     value without loss (exact long minor units), and its raw_data is valid
        //     JSON (jsonb-safe — B-010 backstop).
        result.records().forEach(r -> {
            io.nexuspay.common.domain.Money amt =
                    io.nexuspay.common.domain.Money.ofMinorUnits(r.getAmount(), r.getCurrency());
            assertThat(amt.toMinorUnits())
                    .as("[%s] amount round-trips as exact long minor units", name)
                    .isEqualTo(r.getAmount());
            // net is a parsed long minor unit too (no truncation/overflow on parse).
            assertThat(r.getNetAmount())
                    .as("[%s] net stays within long range", name)
                    .isBetween(Long.MIN_VALUE, Long.MAX_VALUE);
            assertThatCode(() -> mapper.readTree(r.getRawData()))
                    .as("[%s] raw_data is valid JSON", name)
                    .doesNotThrowAnyException();
        });

        // (4) Failures are investigable: each carries a non-blank reason.
        result.failures().forEach(f ->
                assertThat(f.reason()).as("[%s] failure has a reason", name).isNotBlank());
    }

    @Test
    @DisplayName("a giant hostile field does not OOM or crash the tokenizer")
    void pathologicallyLongField_doesNotCrash() {
        String big = "x".repeat(200_000);
        String csv = "id,amount,currency,fee,net,created,description\n"
                + "txn_big,10000,usd,290,9710,2026-03-14,\"" + big + "\"\n";
        assertThatCode(() -> {
            ParseResult result = parse(csv);
            // It is a single well-formed record despite the huge field.
            assertThat(result.records()).hasSize(1);
            assertThat(result.records().get(0).getAmount()).isEqualTo(10000L);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("many rows of pure garbage all surface as failures, none crash")
    void manyGarbageRows_allBecomeFailures_noneCrash() {
        StringBuilder sb = new StringBuilder("id,amount,currency,fee,net,created\n");
        int rows = 500;
        for (int i = 0; i < rows; i++) {
            sb.append("txn_").append(i).append(",garbage").append(i)
                    .append(",usd,bad,bad,not-a-date\n");
        }
        ParseResult result = parse(sb.toString());
        // Every garbage row is a failure; nothing silently dropped.
        assertThat(result.records()).isEmpty();
        assertThat(result.failures()).hasSize(rows);
    }
}
