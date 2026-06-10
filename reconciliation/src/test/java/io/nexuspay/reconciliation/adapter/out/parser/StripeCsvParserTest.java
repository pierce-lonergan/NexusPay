package io.nexuspay.reconciliation.adapter.out.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.reconciliation.domain.SettlementRecord;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression tests for B-010: settlement ingestion stored the raw CSV line in a
 * Postgres {@code jsonb} column, so every INSERT aborted. Parsed records must now
 * carry {@code rawData} that is valid JSON.
 */
class StripeCsvParserTest {

    private final StripeCsvParser parser = new StripeCsvParser();
    private final ObjectMapper mapper = new ObjectMapper();

    private List<SettlementRecord> parse(String csv) {
        return parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                "tenant-1", "run-1");
    }

    @Test
    void parsesRowsAndPopulatesFields() {
        String csv = """
                id,amount,currency,fee,net,created,description,payment_intent
                txn_abc123,10000,usd,290,9710,2026-03-14,Payment,pi_xyz789
                """;

        List<SettlementRecord> records = parse(csv);

        assertThat(records).hasSize(1);
        SettlementRecord r = records.get(0);
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
    void providerIsStripe() {
        assertThat(parser.provider()).isEqualTo("stripe");
    }
}
