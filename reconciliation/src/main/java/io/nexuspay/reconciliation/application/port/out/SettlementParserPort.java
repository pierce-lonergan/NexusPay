package io.nexuspay.reconciliation.application.port.out;

import io.nexuspay.reconciliation.domain.SettlementRecord;

import java.io.InputStream;
import java.util.List;

/**
 * Port for provider-specific settlement file parsing.
 *
 * <p>Each PSP (Stripe, Adyen, HyperSwitch) delivers settlement files in
 * different formats. Implementations of this port parse the raw file content
 * into normalized {@link SettlementRecord} domain objects.</p>
 *
 * @since 0.2.0 (Sprint 2.3)
 */
public interface SettlementParserPort {

    /**
     * Returns the provider name this parser handles (e.g., "stripe", "adyen", "hyperswitch").
     */
    String provider();

    /**
     * Parses a settlement file into settlement records plus a record of any
     * rows that could not be parsed or validated.
     *
     * <p>B-015: a settlement row that cannot be parsed (RFC-4180 corruption,
     * non-numeric amounts, bad dates, missing/ragged columns) must NOT be
     * silently dropped. The parser returns it as a {@link ParseFailure} so the
     * ingestion layer can persist a durable reconciliation exception. Every
     * data row is therefore accounted for: it is either a {@code SettlementRecord}
     * or a {@code ParseFailure} — none vanish.</p>
     *
     * @param input    raw file content
     * @param tenantId tenant context for the parsed records
     * @param runId    the reconciliation run these records belong to
     * @return parsed records and unparseable rows
     */
    ParseResult parse(InputStream input, String tenantId, String runId);

    /**
     * The outcome of parsing a settlement file: the well-formed records that
     * became {@link SettlementRecord}s plus the rows that failed parsing or
     * validation.
     *
     * @param records  successfully parsed settlement records
     * @param failures rows that could not be parsed/validated (B-015)
     */
    record ParseResult(List<SettlementRecord> records, List<ParseFailure> failures) {

        public ParseResult {
            records = records == null ? List.of() : records;
            failures = failures == null ? List.of() : failures;
        }

        /** Convenience factory for parsers/paths with no failures. */
        public static ParseResult ofRecords(List<SettlementRecord> records) {
            return new ParseResult(records, List.of());
        }
    }

    /**
     * A settlement row that could not be parsed or validated. Carries enough
     * context to create a durable, investigable reconciliation exception.
     *
     * <p><strong>{@code lineNumber} is a 1-based LOGICAL record index, not a
     * physical line number.</strong> The header row is index 1; the first
     * non-blank data row is index 2, the next index 3, and so on. Blank lines
     * (whether dropped by the CSV tokenizer's empty-line skipping or recognized
     * as whitespace-only rows) do NOT consume an index, and a quoted field that
     * embeds newlines still counts as a single record. A physical line number is
     * deliberately not exposed because embedded-newline rows and skipped blank
     * lines make it ambiguous; the logical index is stable and reproducible. When
     * the tokenizer aborts mid-stream (e.g. an unterminated quote), the index is
     * the record that was being read when it failed.</p>
     *
     * @param lineNumber 1-based logical record index (header == 1) of the row
     * @param rawLine    the raw source text of the offending row, or an
     *                   {@code <unreadable: ...>} marker when the tokenizer could
     *                   not even decode the row into fields
     * @param reason     why the row failed (parse/validation error message)
     */
    record ParseFailure(long lineNumber, String rawLine, String reason) {
    }
}
