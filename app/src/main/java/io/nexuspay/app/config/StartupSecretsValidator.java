package io.nexuspay.app.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Fail-fast guard against shipping the built-in DEV default secrets to production
 * (B-004). {@code application.yml} carries developer-friendly defaults for the
 * session-token HMAC key, the HyperSwitch webhook secret, and the vault master
 * key so {@code ./gradlew bootRun} works with no setup. If any of those defaults
 * is still in effect under a production-like profile, the app refuses to start —
 * a public, source-controlled signing/encryption key in prod would let anyone
 * forge session tokens or read vaulted card data.
 *
 * <p>SEC-28: the guard also fails boot when a guarded secret resolves to
 * {@code null} or blank under a prod-like profile — an empty HMAC/JWT/vault key
 * is as dangerous as the source-controlled default (it fails open / is trivially
 * forgeable), so an env var set empty in prod must NOT be allowed to boot.</p>
 *
 * <p>Dev ergonomics are preserved: with no profile (the documented local run) or
 * a {@code local}/{@code test}/{@code dev} profile, a default OR a null/blank
 * value only logs a WARN. The hard failure triggers when a {@code prod}-like
 * profile is active or {@code NEXUSPAY_REQUIRE_MANAGED_SECRETS=true} is set
 * (belt-and-suspenders for deployments that don't use a conventional profile
 * name).</p>
 */
@Component
public class StartupSecretsValidator {

    private static final Logger LOG = LoggerFactory.getLogger(StartupSecretsValidator.class);

    /** property key -> the in-source DEV default that must never run in prod. */
    static final Map<String, String> KNOWN_DEFAULTS = Map.of(
            "nexuspay.session.jwt-secret", "dev-session-secret-minimum-32-chars!!",
            "nexuspay.hyperswitch.webhook-secret", "webhook_secret_for_local",
            "nexuspay.vault.encryption.master-key", "ZGV2LXZhdWx0LWtleS1iYXNlNjQtbWluLTMyLWNoYXJzIQ==",
            "nexuspay.dispute.webhook-secret", "dispute_webhook_secret_for_local"
    );

    private static final Set<String> PROD_PROFILES = Set.of("prod", "production", "staging", "stg", "prd");
    private static final Set<String> DEV_PROFILES = Set.of("local", "test", "dev", "default");

    private final Environment env;

    public StartupSecretsValidator(Environment env) {
        this.env = env;
    }

    @PostConstruct
    void validateOnStartup() {
        boolean forced = Boolean.parseBoolean(env.getProperty("nexuspay.security.require-managed-secrets", "false"));
        validate(env.getActiveProfiles(), env::getProperty, forced);
    }

    /**
     * Pure validation (unit-testable without a Spring context).
     *
     * <p>SEC-28: under a production-like profile (or {@code forceManaged}), a guarded secret is an
     * offender when it is EITHER still the in-source DEV default OR resolves to {@code null}/blank — an
     * empty HMAC/JWT/vault key is as dangerous as the public default (it fails open / is trivially
     * forgeable). Under no profile or a {@code local}/{@code test}/{@code dev} profile, the same
     * conditions only WARN, preserving dev ergonomics.</p>
     *
     * @param activeProfiles Spring's active profiles
     * @param resolver       resolves a property key to its effective value
     * @param forceManaged   explicit prod signal (env/property override)
     * @throws IllegalStateException if a guarded secret is the DEV default OR null/blank under prod
     */
    static void validate(String[] activeProfiles, Function<String, String> resolver, boolean forceManaged) {
        boolean prodLike = forceManaged || isProductionProfile(activeProfiles);

        // DEV defaults still in effect (any profile). These warn in dev and fail boot in prod.
        Set<String> defaulted = new TreeSet<>();
        // Guarded secrets that are null/blank. Acceptable (warn) in dev, but FATAL in prod (SEC-28):
        // an empty signing/encryption key is as exploitable as the public default.
        Set<String> blank = new TreeSet<>();
        for (Map.Entry<String, String> e : KNOWN_DEFAULTS.entrySet()) {
            String value = resolver.apply(e.getKey());
            if (Objects.equals(e.getValue(), value)) {
                defaulted.add(e.getKey());
            } else if (value == null || value.isBlank()) {
                blank.add(e.getKey());
            }
        }

        if (defaulted.isEmpty() && blank.isEmpty()) {
            return;
        }

        if (prodLike) {
            // Combined offender set for the boot-refusal message (defaults + null/blank).
            Set<String> offenders = new TreeSet<>(defaulted);
            offenders.addAll(blank);
            throw new IllegalStateException(
                    "Refusing to start: guarded secret(s) are unsafe under a production profile: "
                    + offenders + " (a built-in DEV default, or a null/blank value — both fail open). "
                    + "Provide managed secrets via the matching env vars "
                    + "(NEXUSPAY_SESSION_JWT_SECRET, HYPERSWITCH_WEBHOOK_SECRET, NEXUSPAY_VAULT_MASTER_KEY, "
                    + "DISPUTE_WEBHOOK_SECRET) before deploying.");
        }

        if (!defaulted.isEmpty()) {
            LOG.warn("Using built-in DEV default secret(s): {} — acceptable for local/test only, NEVER "
                    + "production. Set the matching env vars (and a prod profile, or "
                    + "NEXUSPAY_REQUIRE_MANAGED_SECRETS=true) to enforce this in deployed environments.",
                    defaulted);
        }
        if (!blank.isEmpty()) {
            LOG.warn("Guarded secret(s) resolve to null/blank: {} — acceptable for local/test only, "
                    + "NEVER production (an empty signing/encryption key fails open). Set the matching "
                    + "env vars before deploying.", blank);
        }
    }

    private static boolean isProductionProfile(String[] activeProfiles) {
        if (activeProfiles == null) {
            return false;
        }
        for (String p : activeProfiles) {
            String lp = p == null ? "" : p.toLowerCase(java.util.Locale.ROOT);
            if (PROD_PROFILES.contains(lp)) {
                return true;
            }
            // An unrecognized, non-dev profile is treated as production (fail safe).
            if (!lp.isBlank() && !DEV_PROFILES.contains(lp)) {
                return true;
            }
        }
        return false;
    }

    /** Diagnostic helper for the warn-path map (kept for completeness/testing). */
    static Map<String, String> defaultsInUse(Function<String, String> resolver) {
        Map<String, String> hits = new LinkedHashMap<>();
        KNOWN_DEFAULTS.forEach((k, v) -> {
            if (Objects.equals(v, resolver.apply(k))) {
                hits.put(k, v);
            }
        });
        return hits;
    }
}
