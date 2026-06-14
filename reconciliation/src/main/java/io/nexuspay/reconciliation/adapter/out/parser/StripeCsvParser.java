package io.nexuspay.reconciliation.adapter.out.parser;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvParser;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.reconciliation.application.port.out.SettlementParserPort;
import io.nexuspay.reconciliation.domain.SettlementRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses Stripe-format CSV settlement/payout files.
 *
 * <p>Expected CSV columns (header row required):</p>
 * <pre>
 * id,amount,currency,fee,net,created,description,payment_intent
 * txn_abc123,10000,usd,290,9710,2026-03-14,Payment for order #42,pi_xyz789
 * </pre>
 *
 * <p>Amounts are in minor units. Currency is lowercase (converted to uppercase).</p>
 *
 * <p><strong>B-015:</strong> parsing is RFC-4180 correct — quoted fields may
 * contain commas, escaped doubled-quotes ({@code ""}), and embedded
 * newlines/CRLF; leading/trailing whitespace is trimmed and a UTF-8 BOM on the
 * header is stripped. The previous naive {@code line.split(",")} shifted columns
 * whenever a quoted field held a comma, and the resulting numeric-parse failure
 * was swallowed by a {@code log.warn} — money exited reconciliation with no
 * record. Now every data row that cannot be parsed or validated is returned as a
 * {@link ParseFailure} so the ingestion layer persists a durable exception; no
 * settlement row is silently dropped.</p>
 *
 * @since 0.2.0 (Sprint 2.3)
 */
@Component
public class StripeCsvParser implements SettlementParserPort {

    private static final Logger log = LoggerFactory.getLogger(StripeCsvParser.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final char BOM = '\uFEFF';

    /**
     * Shared, thread-safe CSV reader. {@code TRIM_SPACES} trims unquoted leading/
     * trailing whitespace; the default quote char is {@code "} with {@code ""}
     * as the in-field escape. No header schema is applied here — we read raw
     * column arrays so we can normalize the header ourselves (lowercase,
     * BOM-strip) and keep the original case-insensitive index behavior.
     */
    private static final CsvMapper CSV_MAPPER = CsvMapper.builder()
            .enable(CsvParser.Feature.TRIM_SPACES)
            .enable(CsvParser.Feature.WRAP_AS_ARRAY)
            .enable(CsvParser.Feature.SKIP_EMPTY_LINES)
            .build();
    private static final CsvSchema RAW_SCHEMA = CsvSchema.emptySchema()
            .withColumnSeparator(',')
            .withQuoteChar('"');

    @Override
    public String provider() {
        return "stripe";
    }

    @Override
    public ParseResult parse(InputStream input, String tenantId, String runId) {
        List<SettlementRecord> records = new ArrayList<>();
        List<ParseFailure> failures = new ArrayList<>();

        try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
             MappingIterator<List<String>> rows =
                     CSV_MAPPER.readerForListOf(String.class).with(RAW_SCHEMA).readValues(reader)) {

            // Header row.
            if (!rows.hasNext()) {
                throw new IllegalArgumentException("Empty settlement file");
            }
            List<String> headerCells = rows.next();
            Map<String, Integer> headerIndex = new HashMap<>();
            for (int i = 0; i < headerCells.size(); i++) {
                String cell = headerCells.get(i);
                String key = stripBom(cell == null ? "" : cell).trim().toLowerCase();
                headerIndex.putIfAbsent(key, i);
            }
            validateRequiredHeaders(headerIndex, "id", "amount", "currency", "net", "created");
            int headerColumnCount = headerCells.size();

            // Data rows. lineNum is the 1-based LOGICAL record index (header == 1;
            // the first non-blank data row == 2, the next == 3, ...). It is NOT a
            // physical line number — quoted embedded newlines and Jackson's
            // SKIP_EMPTY_LINES make physical line numbers ambiguous, so we expose a
            // stable per-record index instead (see ParseFailure javadoc). The
            // counter advances only for rows we actually attempt to parse, so blank
            // rows never consume an index.
            long lineNum = 1;
            while (true) {
                List<String> fields;
                try {
                    if (!rows.hasNext()) {
                        break;
                    }
                    fields = rows.next();
                } catch (RuntimeException e) {
                    // FIX 1 (B-015): a lone/unterminated quote makes Jackson's CSV
                    // tokenizer throw mid-stream ("Missing closing quote..."). The
                    // Iterator methods (hasNext/next) wrap that as a RuntimeException
                    // — a JsonMappingException becomes RuntimeJsonMappingException,
                    // while a CsvReadException/JsonParseException (both IOException
                    // subtypes) becomes a plain RuntimeException — so we catch the
                    // RuntimeException surface to cover every wrapping form. That
                    // exception must NOT propagate: doing so would discard every
                    // record already collected AND never record the offending row
                    // (whole-file silent loss). Record the tokenizer error as a
                    // durable ParseFailure, stop iterating, and return everything
                    // collected so far. The file-level parse does not throw for a
                    // lone-quote row.
                    String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.warn("Aborting Stripe CSV row iteration at logical record {} (run={}): {}",
                            lineNum + 1, runId, reason);
                    failures.add(new ParseFailure(lineNum + 1, "<unreadable: " + reason + ">", reason));
                    break;
                }

                // A row that decodes to a single empty cell is a blank line; skip
                // WITHOUT consuming a logical-record index.
                if (isBlankRow(fields)) {
                    continue;
                }

                lineNum++;
                String raw = joinRaw(fields);
                try {
                    // FIX 3 (B-015): after correct quote decoding, a row with more
                    // or fewer columns than the header is structurally wrong —
                    // getField resolves by fixed header index, so a ragged row could
                    // otherwise yield a silently-wrong record (shifted values) or
                    // pick up a stray column. Reject it as a failure instead.
                    if (fields.size() != headerColumnCount) {
                        throw new IllegalArgumentException(
                                "Column count mismatch: expected " + headerColumnCount
                                        + " but row has " + fields.size());
                    }

                    SettlementRecord record = new SettlementRecord(
                            PrefixedId.settlementRecord(),
                            runId,
                            tenantId,
                            "stripe",
                            getField(fields, headerIndex, "id"),
                            headerIndex.containsKey("payment_intent")
                                    ? getField(fields, headerIndex, "payment_intent") : null,
                            Long.parseLong(getField(fields, headerIndex, "amount")),
                            getField(fields, headerIndex, "currency").toUpperCase(),
                            headerIndex.containsKey("fee")
                                    ? Long.parseLong(getField(fields, headerIndex, "fee")) : 0L,
                            Long.parseLong(getField(fields, headerIndex, "net")),
                            LocalDate.parse(getField(fields, headerIndex, "created"), DATE_FORMAT)
                                    .atStartOfDay(ZoneOffset.UTC).toInstant()
                    );

                    // raw_data is a jsonb column — store a JSON document, not the
                    // bare CSV line (which is not valid JSON and aborts the INSERT).
                    record.setRawData(JSON.writeValueAsString(Map.of("raw", raw)));
                    records.add(record);

                } catch (Exception e) {
                    // B-015: capture the bad row as a durable failure instead of
                    // a warn-only drop. The ingestion service turns this into a
                    // persisted PARSE_ERROR reconciliation exception.
                    String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.warn("Unparseable Stripe CSV row {} (run={}): {}", lineNum, runId, reason);
                    failures.add(new ParseFailure(lineNum, raw, reason));
                }
            }

            log.info("Parsed {} Stripe settlement records ({} failures) for run: {}",
                    records.size(), failures.size(), runId);

        } catch (IllegalArgumentException e) {
            // File-level problems (empty file, missing required header) abort the
            // whole parse — there is no per-row context to attach.
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Stripe CSV settlement file", e);
        }

        return new ParseResult(records, failures);
    }

    /** A row Jackson decodes to nothing, or a single empty/blank cell, is a blank line. */
    private boolean isBlankRow(List<String> fields) {
        if (fields.isEmpty()) {
            return true;
        }
        if (fields.size() == 1) {
            String only = fields.get(0);
            return only == null || only.isBlank();
        }
        return false;
    }

    /**
     * Reconstructs a human-readable raw representation of a decoded row for the
     * failure record. The original physical bytes are not retained by the
     * streaming parser, so fields are re-joined with commas (decoded values).
     */
    private String joinRaw(List<String> fields) {
        return String.join(",", fields);
    }

    private String getField(List<String> fields, Map<String, Integer> headerIndex, String name) {
        Integer idx = headerIndex.get(name);
        if (idx == null || idx >= fields.size()) {
            throw new IllegalArgumentException("Missing field: " + name);
        }
        String value = fields.get(idx);
        return value == null ? "" : value.trim();
    }

    private void validateRequiredHeaders(Map<String, Integer> headerIndex, String... required) {
        for (String header : required) {
            if (!headerIndex.containsKey(header)) {
                throw new IllegalArgumentException("Missing required CSV header: " + header);
            }
        }
    }

    private static String stripBom(String s) {
        if (s != null && !s.isEmpty() && s.charAt(0) == BOM) {
            return s.substring(1);
        }
        return s;
    }
}
