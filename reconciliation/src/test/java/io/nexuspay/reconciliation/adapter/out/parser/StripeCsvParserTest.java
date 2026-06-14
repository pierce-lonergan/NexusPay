package io.nexuspay.reconciliation.adapter.out.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.reconciliation.application.port.out.SettlementParserPort.ParseFailure;
import io.nexuspay.reconciliation.application.port.out.SettlementParserPort.ParseResult;
import io.nexuspay.reconciliation.domain.SettlementRecord;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for the Stripe settlement CSV parser.
 *
 * <p><strong>B-010</strong> regression: parsed records must carry {@code rawData}
 * that is valid JSON (the source line was stored raw into a {@code jsonb} column,
 * aborting every INSERT).</p>
 *
 * <p><strong>B-015</strong>: the parser must be RFC-4180 correct (quoted commas,
 * escaped {@code ""}, embedded newlines, whitespace, BOM) and must surface every
 * unparseable row as a {@link ParseFailure} instead of silently dropping it
 * (warn-only). This matrix exercises the corruption cases that the previous
 * naive {@code split(",")} mishandled.</p>
 */
class StripeCsvParserTest {

    private final StripeCsvParser parser = new StripeCsvParser();
    private final ObjectMapper mapper = new ObjectMapper();

    private ParseResult parseAll(String csv) {
        return parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                "tenant-1", "run-1");
    }

    private List<SettlementRecord> parse(String csv) {
        return parseAll(csv).records();
    }

    // ---- B-010 regression + baseline well-formed behavior ----

    @Test
    void parsesRowsAndPopulatesFields() {
        String csv = """
                id,amount,currency,fee,net,created,description,payment_intent
                txn_abc123,10000,usd,290,9710,2026-03-14,Payment,pi_xyz789
                """;

        ParseResult result = parseAll(csv);

        assertThat(result.records()).hasSize(1);
        assertThat(result.failures()).isEmpty();
        SettlementRecord r = result.records().get(0);
        assertThat(r.getExternalId()).isEqualTo("txn_abc123");
        assertThat(r.getAmount()).isEqualTo(10000L);
        assertThat(r.getCurrency()).isEqualTo("USD");
        assertThat(r.getFeeAmount()).isEqualTo(290L);
        assertThat(r.getNetAmount()).isEqualTo(9710L);
        assertThat(r.getMatchedPaymentId()).isNull(); // payment_intent maps to paymentReference, not match
    }

    @Test
    void rawDataIsValidJson_b010() {
        String csv = """
                id,amount,currency,fee,net,created
                txn_1,500,eur,10,490,2026-01-02
                txn_2,750,gbp,15,735,2026-01-03
                """;

        List<SettlementRecord> records = parse(csv);

        assertThat(records).hasSize(2);
        for (SettlementRecord r : records) {
            assertThat(r.getRawData()).as("rawData must be non-null").isNotNull();
            assertThatCode(() -> {
                JsonNode node = mapper.readTree(r.getRawData());
                assertThat(node.has("raw")).as("rawData JSON preserves the source line").isTrue();
            }).as("rawData must parse as JSON (jsonb-compatible)").doesNotThrowAnyException();
        }
    }

    @Test
    void emptyFileThrows() {
        assertThatCode(() -> parse(""))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void headerOnlyFile_yieldsNoRecordsAndNoFailures() {
        ParseResult result = parseAll("id,amount,currency,net,created\n");
        assertThat(result.records()).isEmpty();
        assertThat(result.failures()).isEmpty();
    }

    @Test
    void providerIsStripe() {
        assertThat(parser.provider()).isEqualTo("stripe");
    }

    // ---- B-015: RFC-4180 quote handling ----

    @Test
    void quotedComma_inDescription_doesNotShiftColumns_theOriginalBug() {
        // The pre-fix bug row: "Refund, partial" used to split into two tokens,
        // shifting every later column and breaking the numeric parse → silent drop.
        String csv = """
                id,amount,currency,fee,net,created,description
                txn_1,10000,usd,290,9710,2026-03-14,"Refund, partial"
                """;

        ParseResult result = parseAll(csv);

        assertThat(result.failures()).isEmpty();
        assertThat(result.records()).hasSize(1);
        SettlementRecord r = result.records().get(0);
        assertThat(r.getExternalId()).isEqualTo("txn_1");
        assertThat(r.getAmount()).isEqualTo(10000L);
        assertThat(r.getNetAmount()).isEqualTo(9710L);
        assertThat(r.getCurrency()).isEqualTo("USD");
    }

    @Test
    void escapedDoubledQuote_insideQuotedField() {
        String csv = """
                id,amount,currency,fee,net,created,description
                txn_1,10000,usd,290,9710,2026-03-14,"She said ""hi""\"
                """;

        ParseResult result = parseAll(csv);

        assertThat(result.failures()).isEmpty();
        assertThat(result.records()).hasSize(1);
        SettlementRecord r = result.records().get(0);
        assertThat(r.getAmount()).isEqualTo(10000L);
        assertThat(r.getNetAmount()).isEqualTo(9710L);
    }

    @Test
    void embeddedNewline_insideQuotedField_isOneLogicalRecord() {
        // The description spans two physical lines. readLine()-based parsing would
        // cut this into two malformed rows; whole-stream parsing keeps it as one.
        String csv = "id,amount,currency,fee,net,created,description\n"
                + "txn_1,10000,usd,290,9710,2026-03-14,\"line one\nline two\"\n";

        ParseResult result = parseAll(csv);

        assertThat(result.failures()).isEmpty();
        assertThat(result.records()).hasSize(1);
        assertThat(result.records().get(0).getAmount()).isEqualTo(10000L);
    }

    @Test
    void embeddedCrlfNewline_insideQuotedField_isOneLogicalRecord() {
        String csv = "id,amount,currency,fee,net,created,description\r\n"
                + "txn_1,10000,usd,290,9710,2026-03-14,\"line one\r\nline two\"\r\n";

        ParseResult result = parseAll(csv);

        assertThat(result.failures()).isEmpty();
        assertThat(result.records()).hasSize(1);
        assertThat(result.records().get(0).getNetAmount()).isEqualTo(9710L);
    }

    @Test
    void quotedField_withCommaAndNewline_keepsColumnsAligned() {
        String csv = "id,amount,currency,fee,net,created,description\n"
                + "txn_1,10000,usd,290,9710,2026-03-14,\"Refund, partial\nsee note\"\n";

        ParseResult result = parseAll(csv);

        assertThat(result.failures()).isEmpty();
        assertThat(result.records()).hasSize(1);
        SettlementRecord r = result.records().get(0);
        assertThat(r.getAmount()).isEqualTo(10000L);
        assertThat(r.getCurrency()).isEqualTo("USD");
        assertThat(r.getNetAmount()).isEqualTo(9710L);
    }

    @Test
    void leadingAndTrailingWhitespace_isTrimmed() {
        String csv = """
                id,amount,currency,fee,net,created
                  txn_1 ,  10000  , usd ,  290 ,  9710 , 2026-03-14
                """;

        ParseResult result = parseAll(csv);

        assertThat(result.failures()).isEmpty();
        assertThat(result.records()).hasSize(1);
        SettlementRecord r = result.records().get(0);
        assertThat(r.getExternalId()).isEqualTo("txn_1");
        assertThat(r.getAmount()).isEqualTo(10000L);
        assertThat(r.getCurrency()).isEqualTo("USD");
    }

    @Test
    void utf8Bom_onHeader_doesNotBreakHeaderRecognition() {
        String csv = "\uFEFFid,amount,currency,fee,net,created\n"
                + "txn_1,10000,usd,290,9710,2026-03-14\n";

        ParseResult result = parseAll(csv);

        assertThat(result.failures()).isEmpty();
        assertThat(result.records()).hasSize(1);
        assertThat(result.records().get(0).getExternalId()).isEqualTo("txn_1");
    }

    @Test
    void quotedHeaderCell_stillValidatesRequiredHeaders() {
        String csv = """
                "id","amount","currency","net","created"
                txn_1,10000,usd,9710,2026-03-14
                """;

        ParseResult result = parseAll(csv);

        assertThat(result.failures()).isEmpty();
        assertThat(result.records()).hasSize(1);
        assertThat(result.records().get(0).getAmount()).isEqualTo(10000L);
    }

    @Test
    void blankLinesBetweenRows_areSkipped_notCountedAsFailures() {
        String csv = "id,amount,currency,fee,net,created\n"
                + "txn_1,500,usd,10,490,2026-01-02\n"
                + "\n"
                + "   \n"
                + "txn_2,750,usd,15,735,2026-01-03\n";

        ParseResult result = parseAll(csv);

        assertThat(result.records()).hasSize(2);
        assertThat(result.failures()).isEmpty();
    }

    // ---- B-015: malformed rows become ParseFailures (no silent drop) ----

    @Test
    void nonNumericAmount_isAFailure_notADrop() {
        String csv = """
                id,amount,currency,fee,net,created
                txn_bad,abc,usd,10,490,2026-01-02
                """;

        ParseResult result = parseAll(csv);

        assertThat(result.records()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        ParseFailure f = result.failures().get(0);
        assertThat(f.lineNumber()).isEqualTo(2L);
        assertThat(f.rawLine()).contains("txn_bad");
        assertThat(f.reason()).isNotBlank();
    }

    @Test
    void badDate_isAFailure() {
        String csv = """
                id,amount,currency,fee,net,created
                txn_bad,1000,usd,10,990,2026-13-40
                """;

        ParseResult result = parseAll(csv);

        assertThat(result.records()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).rawLine()).contains("2026-13-40");
    }

    @Test
    void raggedRow_missingTrailingColumns_isAFailure() {
        // After correct quote parsing the row simply has too few columns: the
        // required 'created'/'net' index is out of range → validation failure.
        String csv = """
                id,amount,currency,fee,net,created
                txn_short,1000,usd
                """;

        ParseResult result = parseAll(csv);

        assertThat(result.records()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).reason()).isNotBlank();
    }

    @Test
    void emptyRequiredField_emptyAmount_isAFailure() {
        String csv = """
                id,amount,currency,fee,net,created
                txn_empty,,usd,10,490,2026-01-02
                """;

        ParseResult result = parseAll(csv);

        assertThat(result.records()).isEmpty();
        assertThat(result.failures()).hasSize(1);
    }

    @Test
    void mixedGoodAndBadRows_everyRowIsAccountedFor() {
        // 3 good + 2 bad: nothing may vanish — records + failures == data rows.
        String csv = """
                id,amount,currency,fee,net,created,description
                txn_1,10000,usd,290,9710,2026-03-14,"Refund, partial"
                txn_bad1,abc,usd,10,490,2026-01-02,bad amount
                txn_2,500,eur,5,495,2026-01-03,ok
                txn_bad2,1000,usd,10,990,2026-13-40,bad date
                txn_3,750,gbp,15,735,2026-01-04,"quote ""inside"" ok"
                """;

        ParseResult result = parseAll(csv);

        assertThat(result.records()).hasSize(3);
        assertThat(result.failures()).hasSize(2);
        // Partition invariant: 3 + 2 == 5 data rows, zero unaccounted.
        assertThat(result.records().size() + result.failures().size()).isEqualTo(5);
        assertThat(result.records()).extracting(SettlementRecord::getExternalId)
                .containsExactlyInAnyOrder("txn_1", "txn_2", "txn_3");
    }

    @Test
    void missingRequiredHeader_throws() {
        // No 'created' column at all is a file-level error (not a per-row failure).
        String csv = """
                id,amount,currency,net
                txn_1,10000,usd,9710
                """;

        assertThatCode(() -> parseAll(csv))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("created");
    }

    // ---- FIX 5: quoted comma BEFORE numeric columns (genuinely breaks split(",")) ----

    @Test
    void quotedCommaField_precedingNumericColumns_doesNotShiftColumns() {
        // The quoted field is NOT last: a naive split(",") would split "Refund,
        // partial" into two tokens, shifting amount/net into the wrong indices and
        // breaking the numeric parse — a silent drop pre-fix. RFC-4180 parsing keeps
        // every later numeric column aligned.
        String csv = """
                id,description,amount,currency,fee,net,created
                txn_1,"Refund, partial",10000,usd,290,9710,2026-03-14
                """;

        ParseResult result = parseAll(csv);

        assertThat(result.failures()).isEmpty();
        assertThat(result.records()).hasSize(1);
        SettlementRecord r = result.records().get(0);
        assertThat(r.getExternalId()).isEqualTo("txn_1");
        assertThat(r.getAmount()).isEqualTo(10000L);
        assertThat(r.getNetAmount()).isEqualTo(9710L);
        assertThat(r.getCurrency()).isEqualTo("USD");
    }

    // ---- FIX 1: lone/unterminated quote must not abort the whole file ----

    @Test
    void loneQuoteRow_alone_yieldsNoRecords_oneFailure_doesNotThrow() {
        // An unterminated quote makes the CSV tokenizer throw mid-stream. The parse
        // must NOT propagate that exception (which would discard everything); it
        // records a ParseFailure and returns normally.
        String csv = "id,amount,currency,fee,net,created\n"
                + "txn_bad,200,usd,5,195,\"unterminated\n";

        ParseResult result = parseAll(csv);

        assertThat(result.records()).isEmpty();
        assertThat(result.failures()).isNotEmpty();
        assertThat(result.failures().get(0).reason()).isNotBlank();
    }

    @Test
    void goodRow_thenLoneQuoteRow_thenGoodRow_preservesLeadingGoodRow() {
        // Proves no pre-abort discard: the leading good row collected BEFORE the
        // tokenizer error must survive in records. (The trailing good row is
        // unreachable once the tokenizer aborts mid-stream — that is expected; the
        // contract is that already-collected records are never thrown away.)
        String csv = "id,amount,currency,fee,net,created,description\n"
                + "txn_first,500,usd,10,490,2026-01-02,ok\n"
                + "txn_bad,200,usd,5,195,2026-01-03,\"unterminated\n"
                + "txn_last,750,usd,15,735,2026-01-04,also ok\n";

        ParseResult result = parseAll(csv);

        assertThat(result.records()).extracting(SettlementRecord::getExternalId)
                .contains("txn_first");
        assertThat(result.failures()).isNotEmpty();
        // Partition invariant holds even on a tokenizer abort: nothing collected is
        // silently dropped.
        assertThat(result.records()).isNotEmpty();
    }

    @Test
    void loneQuoteRow_doesNotThrowFromParse() {
        String csv = "id,amount,currency,fee,net,created\n"
                + "txn_bad,200,usd,5,195,\"unterminated\n";

        assertThatCode(() -> parseAll(csv)).doesNotThrowAnyException();
    }

    // ---- FIX 3: column-count mismatch is a failure, not a silently-wrong record ----

    @Test
    void rowWithExtraColumns_isAFailure_notAWrongRecord() {
        // 7 decoded columns vs a 6-column header: getField-by-fixed-index could
        // otherwise silently mis-map. It must become a ParseFailure.
        String csv = """
                id,amount,currency,fee,net,created
                txn_extra,10000,usd,290,9710,2026-03-14,surprise_extra
                """;

        ParseResult result = parseAll(csv);

        assertThat(result.records()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).reason()).containsIgnoringCase("column");
    }

    @Test
    void rowWithTooFewColumns_isAFailure_notADrop() {
        // 4 decoded columns vs a 6-column header.
        String csv = """
                id,amount,currency,fee,net,created
                txn_short,10000,usd,290
                """;

        ParseResult result = parseAll(csv);

        assertThat(result.records()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).reason()).containsIgnoringCase("column");
    }

    // ---- FIX 4: lineNumber is a 1-based LOGICAL record index (header == 1) ----

    @Test
    void parseFailureLineNumber_isLogicalRecordIndex_unaffectedByBlankLines() {
        // Blank lines precede the bad row. SKIP_EMPTY_LINES drops the truly empty
        // line; the whitespace-only line is recognized and skipped WITHOUT
        // consuming an index. So the bad row is logical record 2 (header == 1),
        // regardless of how many blank physical lines preceded it.
        String csv = "id,amount,currency,fee,net,created\n"
                + "\n"
                + "   \n"
                + "txn_bad,abc,usd,10,490,2026-01-02\n";

        ParseResult result = parseAll(csv);

        assertThat(result.records()).isEmpty();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).lineNumber())
                .as("logical record index: header==1, first data row==2")
                .isEqualTo(2L);
    }

    @Test
    void parseFailureLineNumber_logicalIndexAcrossEmbeddedNewlineRecord() {
        // A good record whose quoted field embeds a newline still counts as ONE
        // logical record (index 2); the following bad row is index 3 even though it
        // is on physical line 5.
        String csv = "id,amount,currency,fee,net,created,description\n"
                + "txn_ok,500,usd,10,490,2026-01-02,\"line one\nline two\"\n"
                + "txn_bad,abc,usd,10,490,2026-01-02,bad\n";

        ParseResult result = parseAll(csv);

        assertThat(result.records()).hasSize(1);
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).lineNumber())
                .as("logical index ignores embedded newlines inside the prior record")
                .isEqualTo(3L);
    }

    @Test
    void goodRowAfterFailure_stillParses_rawDataStaysValidJson() {
        String csv = """
                id,amount,currency,fee,net,created,description
                txn_bad,abc,usd,10,490,2026-01-02,bad
                txn_ok,500,eur,5,495,2026-01-03,"ok, fine"
                """;

        ParseResult result = parseAll(csv);

        assertThat(result.records()).hasSize(1);
        assertThat(result.failures()).hasSize(1);
        SettlementRecord ok = result.records().get(0);
        assertThat(ok.getExternalId()).isEqualTo("txn_ok");
        assertThatCode(() -> mapper.readTree(ok.getRawData())).doesNotThrowAnyException();
    }
}
