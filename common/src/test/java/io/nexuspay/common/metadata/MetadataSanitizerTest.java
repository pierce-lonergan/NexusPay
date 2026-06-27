package io.nexuspay.common.metadata;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEST-3a: the shared client-metadata strip rule reused by the webhook correlation surface AND the
 * Customer / saved-credential cluster. Locks the reserved-key strip, the {@code __}-prefix server-control
 * strip, depth recursion, and the {@link MetadataSanitizer#MAX_KEYS} cap so neither surface can drift.
 */
class MetadataSanitizerTest {

    @Test
    void dropsForbiddenPanAndCardKeys() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("pan", "4111111111111111");
        in.put("card", "x");
        in.put("number", "y");
        in.put("cvc", "z");
        in.put("cvv", "z");
        in.put("payment_method_data", "x");
        in.put("payment_method", "x");
        in.put("plan", "gold");

        Map<String, Object> out = MetadataSanitizer.sanitize(in);

        assertThat(out).containsExactly(Map.entry("plan", "gold"));
    }

    @Test
    void dropsGateOwnedAuthorityMarkers() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("source", "evil");
        in.put("workflow", "evil");
        in.put("tenant_id", "other-tenant"); // a client-echoed tenant must never become a stored key
        in.put("keep", "ok");

        assertThat(MetadataSanitizer.sanitize(in)).containsExactly(Map.entry("keep", "ok"));
    }

    @Test
    void dropsAnyDoubleUnderscoreReservedKey() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("__livemode", true);
        in.put("__test_outcome", "decline");
        in.put("__anything", "smuggle"); // ANY __ key is server-reserved, not just the known two
        in.put("normal", "ok");

        assertThat(MetadataSanitizer.sanitize(in)).containsExactly(Map.entry("normal", "ok"));
    }

    @Test
    void isCaseInsensitiveOnForbiddenKeys() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("PAN", "4111");
        in.put("Card", "x");
        in.put("ok", "v");

        assertThat(MetadataSanitizer.sanitize(in)).containsExactly(Map.entry("ok", "v"));
    }

    @Test
    void stripsForbiddenKeysAtAnyDepthInNestedMapsAndLists() {
        Map<String, Object> nestedCard = new LinkedHashMap<>();
        nestedCard.put("number", "4111111111111111");
        nestedCard.put("cvc", "123");
        nestedCard.put("brand", "visa"); // legitimate sibling survives

        Map<String, Object> in = new LinkedHashMap<>();
        in.put("billing", nestedCard);
        in.put("history", List.of(Map.of("pan", "4111", "label", "primary")));

        Map<String, Object> out = MetadataSanitizer.sanitize(in);

        @SuppressWarnings("unchecked")
        Map<String, Object> billing = (Map<String, Object>) out.get("billing");
        assertThat(billing).containsExactly(Map.entry("brand", "visa"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) out.get("history");
        assertThat(history.get(0)).containsExactly(Map.entry("label", "primary"));
    }

    @Test
    void overKeyCapDropsToEmpty() {
        Map<String, Object> in = new LinkedHashMap<>();
        for (int i = 0; i <= MetadataSanitizer.MAX_KEYS; i++) {
            in.put("k" + i, "v");
        }
        assertThat(MetadataSanitizer.sanitize(in)).isEmpty();
    }

    @Test
    void nullAndEmptyReturnEmptyMap() {
        assertThat(MetadataSanitizer.sanitize(null)).isEmpty();
        assertThat(MetadataSanitizer.sanitize(Map.of())).isEmpty();
    }
}
