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
 * <p>Dev ergonomics are preserved: with no profile (the documented local run) or
 * a {@code local}/{@code test}/{@code dev} profile, a default only logs a WARN.
 * The hard failure triggers when a {@code prod}-like profile is active or
 * {@code NEXUSPAY_REQUIRE_MANAGED_SECRETS=true} is set (belt-and-suspenders for
 * deployments that don't use a conventional profile name).</p>
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
     * @param activeProfiles Spring's active profiles
     * @param resolver       resolves a property key to its effective value
     * @param forceManaged   explicit prod signal (env/property override)
     * @throws IllegalStateException if a DEV default is in use under prod
     */
    static void validate(String[] activeProfiles, Function<String, String> resolver, boolean forceManaged) {
        Set<String> offenders = new TreeSet<>();
        for (Map.Entry<String, String> e : KNOWN_DEFAULTS.entrySet()) {
            if (Objects.equals(e.getValue(), resolver.apply(e.getKey()))) {
                offenders.add(e.getKey());
            }
        }
        if (offenders.isEmpty()) {
            return;
        }
        if (forceManaged || isProductionProfile(activeProfiles)) {
            throw new IllegalStateException(
                    "Refusing to start: built-in DEV default secret(s) are in effect under a production "
                    + "profile: " + offenders + ". Provide managed secrets via the matching env vars "
                    + "(NEXUSPAY_SESSION_JWT_SECRET, HYPERSWITCH_WEBHOOK_SECRET, NEXUSPAY_VAULT_MASTER_KEY, "
                    + "DISPUTE_WEBHOOK_SECRET) before deploying.");
        }
        LOG.warn("Using built-in DEV default secret(s): {} — acceptable for local/test only, NEVER production. "
                + "Set the matching env vars (and a prod profile, or NEXUSPAY_REQUIRE_MANAGED_SECRETS=true) "
                + "to enforce this in deployed environments.", offenders);
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
