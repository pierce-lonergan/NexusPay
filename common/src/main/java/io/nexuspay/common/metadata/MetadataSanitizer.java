package io.nexuspay.common.metadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared sanitizer for client-supplied free-form {@code metadata} maps (TEST-3a).
 *
 * <p>Lifted out of {@code WebhookMetadataService} so EVERY client-metadata surface (webhook correlation
 * metadata AND the Customer / saved-credential cluster) shares ONE reserved-key strip rule rather than
 * forking it. The rule:</p>
 * <ul>
 *   <li>drops any {@link #FORBIDDEN} key (PAN/card material + the gate-owned authority markers) at ANY
 *       nesting depth, case-insensitively;</li>
 *   <li>drops any {@code __}-prefixed SERVER-RESERVED control key (e.g. {@code __livemode},
 *       {@code __test_outcome}) at ANY depth — a client can never smuggle a server control key into a
 *       persisted/echoed map;</li>
 *   <li>caps the map at {@link #MAX_KEYS} top-level keys — an over-cap map is dropped to {@code {}} rather
 *       than stored partial/truncated.</li>
 * </ul>
 *
 * <p>This sanitizer is value-only and stateless: the {@link #MAX_BYTES} serialized-size cap is enforced by
 * callers that have an {@code ObjectMapper} (it depends on the serialized form), so it is exposed as a
 * constant here but applied at the call site.</p>
 *
 * @since TEST-3a
 */
public final class MetadataSanitizer {

    private MetadataSanitizer() {
    }

    /** Serialized size cap (bytes/chars) — callers drop an over-cap map to {@code {}}. */
    public static final int MAX_BYTES = 4096;

    /** Top-level key cap — an over-cap map is dropped to {@code {}}. */
    public static final int MAX_KEYS = 50;

    /** The reserved server-only prefix: any key starting with {@code __} is server-owned and stripped. */
    public static final String RESERVED_PREFIX = "__";

    /**
     * Keys that must NEVER be persisted (PAN/card material) plus the authority markers the platform owns
     * (so a client-echoed {@code source}/{@code workflow}/{@code tenant_id} is never stored as a key).
     * Matched case-insensitively at any nesting depth. Server-reserved {@code __}-prefixed keys
     * (e.g. {@code __livemode}, {@code __test_outcome}) are handled by the {@link #RESERVED_PREFIX} rule and
     * need not be enumerated here.
     */
    public static final Set<String> FORBIDDEN = Set.of(
            "payment_method_data", "card", "number", "cvc", "cvv", "pan", "payment_method",
            "source", "workflow", "tenant_id");

    /**
     * Returns a copy of {@code metadata} with every {@link #FORBIDDEN} key and every {@code __}-prefixed
     * server-reserved key removed (case-insensitive) at ANY nesting depth, capped at {@link #MAX_KEYS}
     * top-level keys. {@code null}/empty → empty map; over-cap → empty map (store nothing rather than a
     * partial map). The recursion strips forbidden/reserved subtrees inside nested maps/lists too
     * (belt-and-suspenders PAN guard).
     */
    public static Map<String, Object> sanitize(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        if (metadata.size() > MAX_KEYS) {
            // Too many keys — store nothing rather than a partial/truncated map.
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : metadata.entrySet()) {
            String key = e.getKey();
            if (isForbidden(key)) {
                continue;
            }
            out.put(key, scrubValue(e.getValue()));
        }
        return out;
    }

    /**
     * True when a key must be dropped: {@code null}, a {@link #FORBIDDEN} member (case-insensitive), or any
     * {@code __}-prefixed server-reserved control key. Centralised so the top-level and recursive passes
     * apply the identical rule.
     */
    private static boolean isForbidden(String key) {
        if (key == null) {
            return true;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.startsWith(RESERVED_PREFIX) || FORBIDDEN.contains(lower);
    }

    private static Object scrubValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> cleaned = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : nested.entrySet()) {
                Object k = e.getKey();
                String key = k != null ? k.toString() : null;
                if (isForbidden(key)) {
                    continue;
                }
                cleaned.put(key, scrubValue(e.getValue()));
            }
            return cleaned;
        }
        if (value instanceof Iterable<?> list) {
            List<Object> cleaned = new ArrayList<>();
            for (Object item : list) {
                cleaned.add(scrubValue(item));
            }
            return cleaned;
        }
        return value;
    }
}
