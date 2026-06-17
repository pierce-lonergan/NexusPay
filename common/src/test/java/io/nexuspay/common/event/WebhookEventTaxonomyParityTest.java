package io.nexuspay.common.event;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DX-5d: drift guard between the PLATFORM canonical webhook event taxonomy
 * ({@link WebhookEventTaxonomy#CANONICAL}, the Java source of truth) and the TypeScript SDK's
 * hand-maintained {@code WEBHOOK_EVENT_TYPES} union in
 * {@code checkout-sdk/packages/node/src/types.ts}.
 *
 * <p>The two live in different toolchains and were verified consistent by INT-1, but nothing stopped them
 * from silently DRIFTING (someone adds a platform event but forgets the SDK union, or vice-versa). This
 * test fails CI the moment the SDK union and the platform set diverge, so a contributor cannot ship a
 * taxonomy change to only one side. CI checks out the whole repo, so the SDK file is always present.</p>
 */
class WebhookEventTaxonomyParityTest {

    // Mirrors the verified extraction: capture the array body of `WEBHOOK_EVENT_TYPES = [ ... ]`, then
    // pull each quoted dotted-name entry. Kept deliberately simple/literal — the SDK union is a flat list.
    private static final Pattern ARRAY_BLOCK =
            Pattern.compile("WEBHOOK_EVENT_TYPES\\s*=\\s*\\[([\\s\\S]*?)]");
    private static final Pattern ENTRY =
            Pattern.compile("['\"]([a-z0-9_.]+)['\"]");

    @Test
    void sdkUnionMatchesPlatformCanonicalSet() throws IOException {
        Path sdkTypes = repoRoot().resolve("checkout-sdk/packages/node/src/types.ts");
        assertThat(Files.isRegularFile(sdkTypes))
                .as("SDK types file must exist at %s (whole repo is checked out in CI)", sdkTypes)
                .isTrue();

        String src = Files.readString(sdkTypes);
        Matcher block = ARRAY_BLOCK.matcher(src);
        assertThat(block.find())
                .as("could not locate the WEBHOOK_EVENT_TYPES array in %s", sdkTypes)
                .isTrue();

        Set<String> sdkUnion = new LinkedHashSet<>();
        Matcher entry = ENTRY.matcher(block.group(1));
        while (entry.find()) {
            sdkUnion.add(entry.group(1));
        }

        assertThat(sdkUnion)
                .as("SDK WEBHOOK_EVENT_TYPES must EXACTLY equal the platform WebhookEventTaxonomy.CANONICAL "
                        + "set — a taxonomy change must update BOTH the Java platform and the TS SDK")
                .containsExactlyInAnyOrderElementsOf(WebhookEventTaxonomy.CANONICAL);
    }

    /**
     * Resolves the repository root by walking up from the module working directory until the directory
     * containing {@code settings.gradle.kts} is found. Robust to which subproject the test runs from.
     */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "could not locate repo root (no settings.gradle.kts found walking up from "
                        + Path.of("").toAbsolutePath() + ")");
    }
}
