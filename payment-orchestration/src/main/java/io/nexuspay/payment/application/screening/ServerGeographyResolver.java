package io.nexuspay.payment.application.screening;

import io.nexuspay.payment.application.port.fx.MerchantCurrencyPrefsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves SERVER-AUTHORITATIVE geography for the sanctions screen (B-025).
 *
 * <p>The OFAC/cross-border screen must not trust client-supplied country metadata: a caller
 * can omit or forge {@code source_country}/{@code destination_country} to look domestic/clean
 * and evade the screen. This resolver derives geography from sources the SERVER controls:
 * <ul>
 *   <li><b>Destination</b> = the tenant's configured {@code merchant_country}
 *       ({@link MerchantCurrencyPrefsRepository}, tenant-keyed + RLS-protected). The client's
 *       {@code destination_country}/{@code merchant_country} is ADVISORY only — if it disagrees
 *       with the server value, an {@code advisory_destination_mismatch} flag is raised and the
 *       SERVER value is used.</li>
 *   <li><b>Source</b> = a TRUSTED edge-stamped key only ({@code ip_country_trusted}, set by the
 *       controller from a trusted edge header — never by the client). No BIN→issuer-country or
 *       client geo-IP exists today, so when the edge does not stamp the key the source is treated
 *       as UNKNOWN (honest), NOT inferred from the client {@code source_country}/{@code ip_country}.</li>
 * </ul></p>
 *
 * <p>The client's {@code source_country}/{@code ip_country}/{@code destination_country} remain in
 * {@link GateSignals} for the FRAUD engine (advisory), but the resolver never reads them for the
 * sanctions decision.</p>
 *
 * @since B-025
 */
@Service
public class ServerGeographyResolver {

    private static final Logger log = LoggerFactory.getLogger(ServerGeographyResolver.class);

    /** Metadata key the CONTROLLER stamps from a trusted edge header. Never client-settable. */
    public static final String TRUSTED_IP_COUNTRY_KEY = "ip_country_trusted";

    private final MerchantCurrencyPrefsRepository merchantPrefs;

    public ServerGeographyResolver(MerchantCurrencyPrefsRepository merchantPrefs) {
        this.merchantPrefs = merchantPrefs;
    }

    /**
     * Resolved, server-authoritative geography for a payment.
     *
     * @param destinationCountry server-derived ISO-2 destination, or null when unknown
     * @param sourceCountry      trusted-edge ISO-2 source, or null when unknown
     * @param destinationKnown   destination came from server-authoritative config
     * @param sourceKnown        source came from a trusted edge signal
     * @param advisoryFlags      non-authoritative observations (e.g. client/server mismatch)
     */
    public record ResolvedGeography(
            String destinationCountry,
            String sourceCountry,
            boolean destinationKnown,
            boolean sourceKnown,
            List<String> advisoryFlags
    ) {
        /**
         * Cross-border-capable when both legs are known AND differ. When a leg is unknown we
         * CANNOT prove the flow is domestic, so it is treated as cross-border-capable (the gate
         * then fails closed to REVIEW). We never infer "domestic" from a missing leg.
         */
        public boolean crossBorderCapable() {
            if (!destinationKnown || !sourceKnown) {
                return true; // unknown leg → cannot prove domestic → treat as cross-border
            }
            return !destinationCountry.equalsIgnoreCase(sourceCountry);
        }
    }

    /**
     * Resolve geography from the TRUSTED tenant + the controller-stamped trusted edge key.
     * The advisory client {@link GateSignals} are consulted ONLY to raise a mismatch flag,
     * never to drive the decision.
     *
     * @param tenantId      the trusted tenant from the authenticated principal
     * @param clientSignals advisory client-supplied signals (mismatch detection only)
     * @param metadata      the request metadata (read for the trusted edge key only)
     */
    public ResolvedGeography resolve(String tenantId, GateSignals clientSignals, Map<String, Object> metadata) {
        List<String> advisory = new ArrayList<>();

        // --- DESTINATION: server-authoritative merchant config ---
        String serverDestination = null;
        if (tenantId != null && !tenantId.isBlank()) {
            serverDestination = merchantPrefs.findByTenantId(tenantId)
                    .map(MerchantCurrencyPrefsRepository.MerchantCurrencyPrefs::merchantCountry)
                    .map(ServerGeographyResolver::normalize)
                    .orElse(null);
        }
        boolean destinationKnown = serverDestination != null;

        // Advisory: if the CLIENT claimed a destination that disagrees with the server value,
        // flag it (do NOT trust the client value either way).
        String clientDestination = clientSignals != null ? normalize(clientSignals.destinationCountry()) : null;
        if (clientDestination != null && serverDestination != null
                && !clientDestination.equalsIgnoreCase(serverDestination)) {
            advisory.add("advisory_destination_mismatch");
            log.warn("Client-supplied destination_country disagrees with server merchant_country "
                    + "for tenant {} — using server value (client value ignored)", tenantId);
        }

        // --- SOURCE: trusted edge key ONLY (never client source_country/ip_country) ---
        String trustedSource = normalize(strFromMetadata(metadata, TRUSTED_IP_COUNTRY_KEY));
        boolean sourceKnown = trustedSource != null;

        return new ResolvedGeography(serverDestination, trustedSource, destinationKnown, sourceKnown, advisory);
    }

    private static String normalize(String c) {
        if (c == null) {
            return null;
        }
        String t = c.trim().toUpperCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    private static String strFromMetadata(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object v = metadata.get(key);
        return v != null ? v.toString() : null;
    }
}
