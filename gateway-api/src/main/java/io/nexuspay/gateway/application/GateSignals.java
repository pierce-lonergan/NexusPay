package io.nexuspay.gateway.application;

import java.util.Map;

/**
 * Request-derived inputs for the {@link PreAuthorizationGate} that are not part of
 * the core {@code PaymentRequest}: the geography + card/device signals that the
 * sanctions and fraud checks consult.
 *
 * <p>First cut (B-003) sources these from the request metadata (and the card
 * number prefix when a raw PAN is present). Richer derivation — BIN→issuer-country
 * lookup, X-Forwarded-For geo-IP — is a follow-up; absent signals degrade safely
 * (a null country is treated as not-sanctioned by the compliance service, and the
 * fraud rules tolerate missing fields).</p>
 */
public record GateSignals(
        String sourceCountry,
        String destinationCountry,
        String ipAddress,
        String ipCountry,
        String cardBin,
        String cardHash,
        String customerEmail,
        String deviceFingerprintHash
) {

    public static GateSignals none() {
        return new GateSignals(null, null, null, null, null, null, null, null);
    }

    /**
     * Best-effort extraction from a payment request's metadata + payment-method data.
     * Keys are read leniently (snake_case as sent by clients); missing keys → null.
     */
    public static GateSignals fromRequest(Map<String, Object> metadata, String paymentMethodData) {
        Map<String, Object> m = metadata == null ? Map.of() : metadata;
        String ipCountry = str(m, "ip_country");
        return new GateSignals(
                firstNonNull(str(m, "source_country"), ipCountry),
                firstNonNull(str(m, "destination_country"), str(m, "merchant_country")),
                str(m, "ip_address"),
                ipCountry,
                firstNonNull(str(m, "card_bin"), binFromPan(paymentMethodData)),
                str(m, "card_hash"),
                str(m, "customer_email"),
                str(m, "device_fingerprint_hash")
        );
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    /** Returns the first 6 digits if the value looks like a raw PAN; otherwise null. */
    private static String binFromPan(String paymentMethodData) {
        if (paymentMethodData == null) return null;
        String digits = paymentMethodData.replaceAll("\\D", "");
        return digits.length() >= 12 ? digits.substring(0, 6) : null;
    }
}
