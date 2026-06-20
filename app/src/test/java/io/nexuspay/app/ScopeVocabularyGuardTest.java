package io.nexuspay.app;

import io.nexuspay.common.api.ApiScope;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DX-5c-ii guard: every scope string referenced in a {@code @PreAuthorize("... @scopeAuth.has('X') ...")}
 * across ALL controllers (every module is on the {@code :app} test classpath) MUST be a member of the
 * single {@link ApiScope} vocabulary. Prevents typo drift between an annotation and the vocabulary — a
 * mistyped scope would otherwise silently lock out (or, worse, never match) a capability.
 *
 * <p>Reflection-only (no Spring context boot): scans the classpath for {@code @RestController} types and
 * inspects their {@code @PreAuthorize} expressions both at the type and method level.</p>
 */
class ScopeVocabularyGuardTest {

    // Matches @scopeAuth.has('<scope>') / @scopeAuth.has("<scope>") with single or double quotes.
    private static final Pattern SCOPE_CALL =
            Pattern.compile("@scopeAuth\\.has\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");

    @Test
    void everyScopeInPreAuthorizeIsAKnownApiScope() {
        var provider = new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<String> referenced = new ArrayList<>();
        int controllersScanned = 0;

        for (var bd : provider.findCandidateComponents("io.nexuspay")) {
            Class<?> controller;
            try {
                controller = Class.forName(bd.getBeanClassName());
            } catch (ClassNotFoundException e) {
                continue;
            }
            controllersScanned++;
            collect(controller.getAnnotation(PreAuthorize.class), referenced);
            for (Method m : controller.getDeclaredMethods()) {
                collect(m.getAnnotation(PreAuthorize.class), referenced);
            }
        }

        // Sanity: the scan must actually find controllers (otherwise the guard would be vacuously true).
        assertThat(controllersScanned).as("controllers found on the :app test classpath").isGreaterThan(5);
        // The money-critical batch must contribute at least one scope reference.
        assertThat(referenced).as("at least one @scopeAuth.has(...) scope must be present").isNotEmpty();

        // Every referenced scope must be a member of the single vocabulary — fail-closed against drift.
        assertThat(ApiScope.VALID).as("all @scopeAuth.has(...) scopes are in the ApiScope vocabulary")
                .containsAll(referenced);
    }

    private static void collect(PreAuthorize pa, List<String> out) {
        if (pa == null) {
            return;
        }
        Matcher matcher = SCOPE_CALL.matcher(pa.value());
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
    }
}
