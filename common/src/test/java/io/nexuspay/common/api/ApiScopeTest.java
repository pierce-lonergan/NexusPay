package io.nexuspay.common.api;

import io.nexuspay.common.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DX-5c-ii: the scope vocabulary is the single source of truth and FAILS CLOSED on unknown tokens at the
 * parse boundary. {@code parseCsv} accepts a valid subset, rejects any unknown token
 * ({@link InvalidRequestException}), and maps blank/null to an EMPTY (== UNRESTRICTED) set.
 */
class ApiScopeTest {

    @Test
    void vocabulary_containsExactlyTheDefinedScopes() {
        assertThat(ApiScope.VALID).containsExactlyInAnyOrder(
                "payments:read", "payments:write",
                "refunds:read", "refunds:write",
                "payouts:read", "payouts:write",
                "webhooks:read", "webhooks:write",
                "keys:write",
                "vault:read", "vault:write",
                "disputes:read", "disputes:write",
                "customers:read", "customers:write",
                "test:write");
    }

    @Test
    void isValid_trueForKnown_falseForUnknownOrNull() {
        assertThat(ApiScope.isValid("payments:write")).isTrue();
        assertThat(ApiScope.isValid("payments:delete")).isFalse(); // not in vocabulary
        assertThat(ApiScope.isValid("PAYMENTS:WRITE")).isFalse();  // case-sensitive
        assertThat(ApiScope.isValid(null)).isFalse();
    }

    @Test
    void parseCsv_acceptsValidSubset() {
        Set<String> parsed = ApiScope.parseCsv("payments:read, refunds:write ,payouts:read");
        assertThat(parsed).containsExactlyInAnyOrder("payments:read", "refunds:write", "payouts:read");
    }

    @Test
    void parseCsv_rejectsUnknownScope_failClosed() {
        assertThatThrownBy(() -> ApiScope.parseCsv("payments:read,not_a_scope"))
                .isInstanceOf(InvalidRequestException.class)
                .extracting(e -> ((InvalidRequestException) e).getErrorCode())
                .isEqualTo("invalid_scope");
    }

    @Test
    void parseCsv_blankOrNull_yieldsEmptyUnrestrictedSet() {
        assertThat(ApiScope.parseCsv(null)).isEmpty();
        assertThat(ApiScope.parseCsv("")).isEmpty();
        assertThat(ApiScope.parseCsv("   ")).isEmpty();
        // Empty segments between commas carry no scope and are tolerated, not rejected.
        assertThat(ApiScope.parseCsv("payments:read,,")).containsExactly("payments:read");
    }

    @Test
    void toCanonicalCsv_nullForEmpty_canonicalOrderForNonEmpty() {
        assertThat(ApiScope.toCanonicalCsv(null)).isNull();
        assertThat(ApiScope.toCanonicalCsv(Set.of())).isNull();
        // Declaration order is stable regardless of input set iteration order.
        assertThat(ApiScope.toCanonicalCsv(Set.of("payments:write", "payments:read")))
                .isEqualTo("payments:read,payments:write");
    }

    @Test
    void toCanonicalCsv_rejectsUnknownScope_failClosed() {
        assertThatThrownBy(() -> ApiScope.toCanonicalCsv(Set.of("bogus:scope")))
                .isInstanceOf(InvalidRequestException.class);
    }
}
