package io.nexuspay.reconciliation.adapter.out.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.reconciliation.application.port.out.SettlementParserPort;
import io.nexuspay.reconciliation.domain.SettlementRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses HyperSwitch JSON settlement export files.
 *
 * <p>Expected format: JSON array of settlement objects, each containing:</p>
 * <pre>{@code
 * {
 *   "payment_id": "pay_abc123",
 *   "amount": 10000,
 *   "currency": "USD",
 *   "fee": 290,
 *   "net": 9710,
 *   "settled_at": "2026-03-14T00:00:00Z",
 *   "connector_transaction_id": "ch_xyz"
 * }
 * }</pre>
 *
 * @since 0.2.0 (Sprint 2.3)
 */
@Component
public class HyperSwitchJsonParser implements SettlementParserPort {

    private static final Logger log = LoggerFactory.getLogger(HyperSwitchJsonParser.class);

    private final ObjectMapper objectMapper;

    public HyperSwitchJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String provider() {
        return "hyperswitch";
    }

    @Override
    public List<SettlementRecord> parse(InputStream input, String tenantId, String runId) {
        List<SettlementRecord> records = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(input);

            if (!root.isArray()) {
                throw new IllegalArgumentException("Expected JSON array of settlement records");
            }

            for (JsonNode node : root) {
                SettlementRecord record = new SettlementRecord(
                        PrefixedId.settlementRecord(),
                        runId,
                        tenantId,
                        "hyperswitch",
                        getTextOrThrow(node, "payment_id"),
                        node.has("merchant_reference") ? node.get("merchant_reference").asText() : null,
                        node.get("amount").asLong(),
                        getTextOrThrow(node, "currency"),
                        node.has("fee") ? node.get("fee").asLong() : 0L,
                        node.has("net") ? node.get("net").asLong() : node.get("amount").asLong(),
                        Instant.parse(getTextOrThrow(node, "settled_at"))
                );

                record.setRawData(objectMapper.writeValueAsString(node));
                records.add(record);
            }

            log.info("Parsed {} HyperSwitch settlement records for run: {}", records.size(), runId);

        } catch (IOException e) {
            throw new RuntimeException("Failed to parse HyperSwitch settlement file", e);
        }

        return records;
    }

    private String getTextOrThrow(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return node.get(field).asText();
    }
}
