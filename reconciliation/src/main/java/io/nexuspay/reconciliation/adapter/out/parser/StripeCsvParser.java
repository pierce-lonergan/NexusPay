package io.nexuspay.reconciliation.adapter.out.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.reconciliation.application.port.out.SettlementParserPort;
import io.nexuspay.reconciliation.domain.SettlementRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
 * @since 0.2.0 (Sprint 2.3)
 */
@Component
public class StripeCsvParser implements SettlementParserPort {

    private static final Logger log = LoggerFactory.getLogger(StripeCsvParser.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String provider() {
        return "stripe";
    }

    @Override
    public List<SettlementRecord> parse(InputStream input, String tenantId, String runId) {
        List<SettlementRecord> records = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            // Parse header
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IllegalArgumentException("Empty settlement file");
            }

            String[] headers = headerLine.split(",");
            Map<String, Integer> headerIndex = new HashMap<>();
            for (int i = 0; i < headers.length; i++) {
                headerIndex.put(headers[i].trim().toLowerCase(), i);
            }

            validateRequiredHeaders(headerIndex, "id", "amount", "currency", "net", "created");

            // Parse data rows
            String line;
            int lineNum = 1;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (line.isBlank()) continue;

                try {
                    String[] fields = line.split(",", -1);
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
                    record.setRawData(MAPPER.writeValueAsString(Map.of("raw", line)));
                    records.add(record);

                } catch (Exception e) {
                    log.warn("Failed to parse line {} of Stripe CSV: {}", lineNum, e.getMessage());
                }
            }

            log.info("Parsed {} Stripe settlement records for run: {}", records.size(), runId);

        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Stripe CSV settlement file", e);
        }

        return records;
    }

    private String getField(String[] fields, Map<String, Integer> headerIndex, String name) {
        Integer idx = headerIndex.get(name);
        if (idx == null || idx >= fields.length) {
            throw new IllegalArgumentException("Missing field: " + name);
        }
        return fields[idx].trim();
    }

    private void validateRequiredHeaders(Map<String, Integer> headerIndex, String... required) {
        for (String header : required) {
            if (!headerIndex.containsKey(header)) {
                throw new IllegalArgumentException("Missing required CSV header: " + header);
            }
        }
    }
}
