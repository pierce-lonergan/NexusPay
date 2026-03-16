package io.nexuspay.common.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Applies a chain of {@link EventUpcaster}s to transform event payloads
 * from their stored version to the current version.
 *
 * <p>Upcasters are auto-discovered via Spring's component scanning. At startup,
 * the chain validates that there are no gaps or duplicates in the version sequence
 * for each event type.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * String currentPayload = upcasterChain.upcast("PaymentCaptured", 1, rawPayload);
 * }</pre>
 *
 * @since 0.2.0 (Sprint 2.2)
 */
@Component
public class EventUpcasterChain {

    private static final Logger log = LoggerFactory.getLogger(EventUpcasterChain.class);

    /**
     * Map of eventType → (sorted map of fromVersion → upcaster).
     */
    private final Map<String, TreeMap<Integer, EventUpcaster>> upcasters;

    public EventUpcasterChain(List<EventUpcaster> registeredUpcasters) {
        this.upcasters = new HashMap<>();

        for (EventUpcaster upcaster : registeredUpcasters) {
            upcasters
                    .computeIfAbsent(upcaster.eventType(), k -> new TreeMap<>())
                    .put(upcaster.fromVersion(), upcaster);
        }

        // Validate chain integrity at startup
        for (var entry : upcasters.entrySet()) {
            validateChain(entry.getKey(), entry.getValue());
        }

        log.info("EventUpcasterChain initialized: {} event types, {} total upcasters",
                upcasters.size(), registeredUpcasters.size());
    }

    /**
     * Upcasts a payload from the given version to the latest version.
     *
     * @param eventType   the event type (e.g., "PaymentCaptured")
     * @param fromVersion the version of the stored payload
     * @param payload     the raw JSON payload
     * @return the upcasted payload at the latest version, or the original if no upcasting needed
     */
    public String upcast(String eventType, int fromVersion, String payload) {
        TreeMap<Integer, EventUpcaster> chain = upcasters.get(eventType);
        if (chain == null || chain.isEmpty()) {
            return payload;
        }

        String current = payload;
        int currentVersion = fromVersion;

        // Walk the chain: fromVersion → fromVersion+1 → ... → latest
        while (chain.containsKey(currentVersion)) {
            EventUpcaster upcaster = chain.get(currentVersion);
            log.debug("Upcasting {}: v{} → v{}", eventType, upcaster.fromVersion(), upcaster.toVersion());
            current = upcaster.upcast(current);
            currentVersion = upcaster.toVersion();
        }

        if (currentVersion != fromVersion) {
            log.debug("Upcasted {}: v{} → v{}", eventType, fromVersion, currentVersion);
        }

        return current;
    }

    /**
     * Returns the latest version for a given event type.
     * If no upcasters exist, returns 1 (baseline).
     */
    public int latestVersion(String eventType) {
        TreeMap<Integer, EventUpcaster> chain = upcasters.get(eventType);
        if (chain == null || chain.isEmpty()) {
            return 1;
        }
        // Latest version = toVersion of the last upcaster in the chain
        return chain.lastEntry().getValue().toVersion();
    }

    /**
     * Upcasts an Avro GenericRecord from the given version to the latest version.
     * Delegates to each upcaster's {@link EventUpcaster#upcast(org.apache.avro.generic.GenericRecord)}
     * method for Avro-native transformations.
     *
     * @param eventType   the event type
     * @param fromVersion the version of the stored record
     * @param record      the Avro GenericRecord to upcast
     * @return the upcasted record at the latest version
     * @since 0.3.0 (Sprint 3.4)
     */
    public org.apache.avro.generic.GenericRecord upcast(String eventType, int fromVersion,
                                                         org.apache.avro.generic.GenericRecord record) {
        TreeMap<Integer, EventUpcaster> chain = upcasters.get(eventType);
        if (chain == null || chain.isEmpty()) {
            return record;
        }

        var current = record;
        int currentVersion = fromVersion;

        while (chain.containsKey(currentVersion)) {
            EventUpcaster upcaster = chain.get(currentVersion);
            log.debug("Upcasting Avro {}: v{} → v{}", eventType, upcaster.fromVersion(), upcaster.toVersion());
            current = upcaster.upcast(current);
            currentVersion = upcaster.toVersion();
        }

        return current;
    }

    private void validateChain(String eventType, TreeMap<Integer, EventUpcaster> chain) {
        int expectedFrom = chain.firstKey();
        for (var entry : chain.entrySet()) {
            EventUpcaster upcaster = entry.getValue();
            if (upcaster.fromVersion() != expectedFrom) {
                log.warn("Gap in upcaster chain for {}: expected fromVersion={}, found={}",
                        eventType, expectedFrom, upcaster.fromVersion());
            }
            expectedFrom = upcaster.toVersion();
        }
    }
}
