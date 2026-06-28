package io.nexuspay.gateway.util;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * GAP-079 (critique v3 F6): the SINGLE SOURCE OF TRUTH for the idempotency Redis-key caller scope.
 *
 * <p>The idempotency store keys every entry as {@code idempotency:{callerScope}:{idempotencyKey}}, where
 * {@code callerScope} = the first 8 bytes of {@code SHA-256(Authorization header)} as hex (or {@code "anon"}
 * when unauthenticated). This derivation is used by BOTH {@code IdempotencyFilter} (which writes the keys)
 * and {@code TestIdempotencyController} (which inspects/clears them). Extracting it here means the two can
 * NEVER drift — a drift would let the inspect/clear look at the WRONG scope.</p>
 *
 * <p><b>IDOR-safe by construction:</b> {@code callerScope} hashes THIS caller's own Authorization header, so
 * a caller can only ever derive — and therefore only ever match — keys under their own scope. You cannot
 * compute another tenant's auth hash, so you can never enumerate or clear another tenant's keys. The raw
 * secret is never stored (only its hash prefix).</p>
 *
 * @since GAP-079
 */
public final class IdempotencyScope {

    /** Common Redis key namespace for idempotency entries. */
    public static final String KEY_NAMESPACE = "idempotency:";

    private static final String ANON_SCOPE = "anon";

    private IdempotencyScope() {
        // Utility class
    }

    /**
     * Derives the stable per-caller scope from the request's {@code Authorization} header — the first 8
     * bytes of {@code SHA-256(auth)} as hex, or {@code "anon"} when there is no Authorization header (or the
     * hash algorithm is unavailable, fail-safe to the shared anon scope). The raw secret never leaves this
     * method.
     *
     * @param request the current HTTP request
     * @return the 16-hex caller scope, or {@code "anon"}
     */
    public static String forRequest(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || auth.isBlank()) {
            return ANON_SCOPE;
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(auth.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            return ANON_SCOPE;
        }
    }

    /**
     * The full key prefix for the caller — {@code "idempotency:{scope}:"}. A specific entry is
     * {@code keyPrefix(req) + idempotencyKey}; the SCAN match pattern is {@code keyPrefix(req) + "*"}.
     *
     * @param request the current HTTP request
     * @return the per-caller key prefix
     */
    public static String keyPrefix(HttpServletRequest request) {
        return KEY_NAMESPACE + forRequest(request) + ":";
    }
}
