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
     * Parses a settlement file into a list of settlement records.
     *
     * @param input    raw file content
     * @param tenantId tenant context for the parsed records
     * @param runId    the reconciliation run these records belong to
     * @return parsed settlement records
     */
    List<SettlementRecord> parse(InputStream input, String tenantId, String runId);
}
