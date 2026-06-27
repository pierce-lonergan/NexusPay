package io.nexuspay.common.api;

import io.nexuspay.common.exception.InvalidRequestException;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * DX-5c-ii: the SINGLE SOURCE OF TRUTH for per-API-key scope strings.
 *
 * <p>A scope is a Stripe-style {@code resource:action} token. An API key MAY carry a set of scopes
 * that NARROW what it can do ON TOP OF its role; a key with NO scopes (NULL / empty) is UNRESTRICTED
 * (role-based only) — the back-compat default. Scopes can only ever NARROW a role, never widen it: the
 * per-endpoint {@code @PreAuthorize} AND-composes the role check with a scope check, so a key still needs
 * the role AND (if scoped) the matching scope.</p>
 *
 * <p>Creation validates supplied scopes against this vocabulary and FAILS CLOSED on any unknown token
 * (see {@link #parseCsv(String)}): an unknown scope is rejected at mint time rather than silently
 * persisted, so a typo can never become a permanently-granted (or permanently-denied) capability.</p>
 *
 * <p>This enum is the ONLY place scope strings are defined; the enforcement bean, the key-create path,
 * and a guard test all reference {@link #VALID} / {@link #isValid(String)} so the set can never drift.</p>
 *
 * @since DX-5c-ii
 */
public enum ApiScope {

    PAYMENTS_READ("payments:read"),
    PAYMENTS_WRITE("payments:write"),
    REFUNDS_READ("refunds:read"),
    REFUNDS_WRITE("refunds:write"),
    PAYOUTS_READ("payouts:read"),
    PAYOUTS_WRITE("payouts:write"),
    WEBHOOKS_READ("webhooks:read"),
    WEBHOOKS_WRITE("webhooks:write"),
    KEYS_WRITE("keys:write"),
    VAULT_READ("vault:read"),
    VAULT_WRITE("vault:write"),
    DISPUTES_READ("disputes:read"),
    DISPUTES_WRITE("disputes:write"),
    CUSTOMERS_READ("customers:read"),
    CUSTOMERS_WRITE("customers:write");

    private final String value;

    ApiScope(String value) {
        this.value = value;
    }

    /** The canonical wire string for this scope (e.g. {@code "payments:write"}). */
    public String value() {
        return value;
    }

    /** Canonical, immutable set of ALL valid scope strings — the single vocabulary. */
    public static final Set<String> VALID;

    static {
        // LinkedHashSet preserves declaration order for stable iteration/logging; wrapped unmodifiable.
        Set<String> all = new LinkedHashSet<>();
        for (ApiScope s : values()) {
            all.add(s.value);
        }
        VALID = Set.copyOf(all);
    }

    /**
     * @return {@code true} iff {@code scope} is a member of the canonical vocabulary. A {@code null} is
     *         never valid.
     */
    public static boolean isValid(String scope) {
        return scope != null && VALID.contains(scope);
    }

    /**
     * Parses a comma-delimited scope list into a normalized {@link Set} of canonical scope strings.
     *
     * <p>FAIL-CLOSED: every non-blank token MUST be a member of {@link #VALID}; an unknown token throws
     * {@link InvalidRequestException} (HTTP 400) so a bad scope is rejected at the boundary rather than
     * silently persisted. Tokens are trimmed; blank tokens between commas (e.g. {@code "a,,b"}) are
     * ignored.</p>
     *
     * <p>A {@code null}, empty, or all-blank csv returns an EMPTY set — which means UNRESTRICTED
     * (role-based, back-compat), NOT "locked out". The caller (key create) persists {@code null} for an
     * empty set; the principal treats null/empty identically as unrestricted.</p>
     *
     * @param csv comma-delimited scopes, or {@code null}/blank for none
     * @return an immutable set of canonical scope strings (possibly empty == unrestricted)
     * @throws InvalidRequestException if any token is not a member of {@link #VALID}
     */
    public static Set<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of(); // empty == UNRESTRICTED (back-compat)
        }
        Set<String> parsed = new LinkedHashSet<>();
        for (String raw : csv.split(",")) {
            String token = raw.trim();
            if (token.isEmpty()) {
                continue; // tolerate empty segments ("a,,b") — they carry no scope
            }
            if (!VALID.contains(token)) {
                // Fail closed: never persist an unknown scope. Caller-caused -> 400.
                throw new InvalidRequestException("Unknown API scope: " + token, "invalid_scope");
            }
            parsed.add(token);
        }
        return Set.copyOf(parsed);
    }

    /**
     * Validates a set of scope strings (fail-closed) and renders the CANONICAL csv to persist, or
     * {@code null} when the set is null/empty (UNRESTRICTED). Used by the key-create path so creation
     * and {@link #parseCsv(String)} share one validator.
     *
     * @param scopes scope strings, or {@code null}/empty for unrestricted
     * @return the canonical comma-delimited csv, or {@code null} for unrestricted
     * @throws InvalidRequestException if any element is not a member of {@link #VALID}
     */
    public static String toCanonicalCsv(Set<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return null; // unrestricted
        }
        // Re-validate and normalize order via the enum declaration order for a stable persisted string.
        Set<String> ordered = new LinkedHashSet<>();
        for (ApiScope s : values()) {
            if (scopes.contains(s.value)) {
                ordered.add(s.value);
            }
        }
        // Any leftover member not matched above is an unknown token -> fail closed.
        for (String scope : scopes) {
            if (!VALID.contains(scope)) {
                throw new InvalidRequestException("Unknown API scope: " + scope, "invalid_scope");
            }
        }
        return String.join(",", ordered);
    }
}
