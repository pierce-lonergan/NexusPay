package io.nexuspay.app.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-004: the app must refuse to start with built-in DEV default secrets under a
 * production profile, while staying frictionless for local/test runs.
 */
class StartupSecretsValidatorTest {

    /** A resolver where every secret is still the in-source DEV default. */
    private static Function<String, String> allDefaults() {
        return StartupSecretsValidator.KNOWN_DEFAULTS::get;
    }

    /** A resolver where every secret has been overridden with a managed value. */
    private static Function<String, String> allManaged() {
        Map<String, String> m = new HashMap<>();
        StartupSecretsValidator.KNOWN_DEFAULTS.keySet()
                .forEach(k -> m.put(k, "managed-" + k.hashCode()));
        return m::get;
    }

    @Test
    void throwsWhenDefaultsUsedUnderProdProfile() {
        assertThatThrownBy(() ->
                StartupSecretsValidator.validate(new String[]{"prod"}, allDefaults(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start")
                .hasMessageContaining("nexuspay.session.jwt-secret");
    }

    @Test
    void throwsWhenForcedEvenWithoutProdProfile() {
        assertThatThrownBy(() ->
                StartupSecretsValidator.validate(new String[]{}, allDefaults(), true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void throwsForUnrecognizedNonDevProfile() {
        // An unknown profile is treated as production (fail safe).
        assertThatThrownBy(() ->
                StartupSecretsValidator.validate(new String[]{"prod-eu"}, allDefaults(), false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allowsDefaultsUnderNoProfile() {
        // The documented `./gradlew bootRun` (no profile) must still work.
        assertThatCode(() ->
                StartupSecretsValidator.validate(new String[]{}, allDefaults(), false))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsDefaultsUnderLocalAndTestProfiles() {
        assertThatCode(() ->
                StartupSecretsValidator.validate(new String[]{"local"}, allDefaults(), false))
                .doesNotThrowAnyException();
        assertThatCode(() ->
                StartupSecretsValidator.validate(new String[]{"test"}, allDefaults(), false))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsProdWhenSecretsAreManaged() {
        assertThatCode(() ->
                StartupSecretsValidator.validate(new String[]{"prod"}, allManaged(), true))
                .doesNotThrowAnyException();
    }

    @Test
    void knownDefaultsStayInSyncWithApplicationYml() throws Exception {
        // Drift guard: if someone changes a default in application.yml without
        // updating KNOWN_DEFAULTS, this security control would silently fail open.
        String yml;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertThat(in).as("application.yml on the test classpath").isNotNull();
            yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        StartupSecretsValidator.KNOWN_DEFAULTS.forEach((key, def) ->
                assertThat(yml)
                        .as("application.yml must still carry the default for " + key
                                + " that KNOWN_DEFAULTS guards against")
                        .contains(def));
    }

    @Test
    void throwsWhenDisputeWebhookSecretDefaultUsedUnderProdProfile() {
        // SEC-BATCH-2: the money-moving dispute webhook HMAC key is guarded too —
        // a prod deploy that forgets DISPUTE_WEBHOOK_SECRET (so the source-controlled
        // default is in effect) must REFUSE TO BOOT. Otherwise an attacker reads the
        // repo default, signs a forged dispute.opened, and posts a chargeback reserve.
        Map<String, String> onlyDisputeDefault = new HashMap<>();
        StartupSecretsValidator.KNOWN_DEFAULTS.keySet()
                .forEach(k -> onlyDisputeDefault.put(k, "managed-" + k.hashCode()));
        onlyDisputeDefault.put("nexuspay.dispute.webhook-secret", "dispute_webhook_secret_for_local");

        assertThatThrownBy(() ->
                StartupSecretsValidator.validate(new String[]{"prod"}, onlyDisputeDefault::get, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start")
                .hasMessageContaining("nexuspay.dispute.webhook-secret")
                .hasMessageContaining("DISPUTE_WEBHOOK_SECRET");
    }

    // ---- SEC-28: a guarded secret that is null/blank is as dangerous as the public default ----

    /** A resolver: every secret managed (non-default, non-blank) EXCEPT one key forced to {@code value}. */
    private static Function<String, String> allManagedExcept(String key, String value) {
        Map<String, String> m = new HashMap<>();
        StartupSecretsValidator.KNOWN_DEFAULTS.keySet()
                .forEach(k -> m.put(k, "managed-" + k.hashCode()));
        m.put(key, value);
        return m::get;
    }

    @Test
    void throwsWhenGuardedSecretIsBlankUnderProdProfile() {
        // SEC-28: an env var set EMPTY in prod (blank HMAC key) must REFUSE TO BOOT — a blank webhook
        // secret fails open (HyperSwitch/dispute gates reject, but a blank JWT/vault key is forgeable).
        assertThatThrownBy(() ->
                StartupSecretsValidator.validate(new String[]{"prod"},
                        allManagedExcept("nexuspay.hyperswitch.webhook-secret", ""), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start")
                .hasMessageContaining("nexuspay.hyperswitch.webhook-secret");
    }

    @Test
    void throwsWhenGuardedSecretIsNullUnderProdProfile() {
        // SEC-28: a guarded key resolving to null (env var entirely unset, no default left) under prod.
        assertThatThrownBy(() ->
                StartupSecretsValidator.validate(new String[]{"prod"},
                        allManagedExcept("nexuspay.session.jwt-secret", null), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to start")
                .hasMessageContaining("nexuspay.session.jwt-secret");
    }

    @Test
    void throwsWhenGuardedSecretIsBlankUnderForceManaged() {
        // SEC-28: the explicit prod signal (NEXUSPAY_REQUIRE_MANAGED_SECRETS=true) also catches blanks,
        // even with no recognised prod profile name.
        assertThatThrownBy(() ->
                StartupSecretsValidator.validate(new String[]{},
                        allManagedExcept("nexuspay.vault.encryption.master-key", "   "), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nexuspay.vault.encryption.master-key");
    }

    @Test
    void allowsProdWhenSecretsAreManagedAndNonBlank() {
        // CONTROL: managed (non-default, non-blank) secrets under prod must still boot — the new
        // blank check must not regress the existing managed-ok path.
        assertThatCode(() ->
                StartupSecretsValidator.validate(new String[]{"prod"}, allManaged(), true))
                .doesNotThrowAnyException();
    }

    @Test
    void blankSecretUnderDevProfile_warnsButDoesNotThrow() {
        // SEC-28: dev ergonomics preserved — a blank secret under local/test/dev (or no profile) only
        // WARNs, never blocks the local run.
        assertThatCode(() ->
                StartupSecretsValidator.validate(new String[]{"local"},
                        allManagedExcept("nexuspay.dispute.webhook-secret", ""), false))
                .doesNotThrowAnyException();
        assertThatCode(() ->
                StartupSecretsValidator.validate(new String[]{},
                        allManagedExcept("nexuspay.dispute.webhook-secret", ""), false))
                .doesNotThrowAnyException();
    }

    @Test
    void detectsExactlyTheDefaultedKeys() {
        // One managed, two still default -> only the two are flagged.
        Map<String, String> mixed = new HashMap<>(StartupSecretsValidator.KNOWN_DEFAULTS);
        mixed.put("nexuspay.session.jwt-secret", "a-real-managed-secret");
        assertThatThrownBy(() ->
                StartupSecretsValidator.validate(new String[]{"prod"}, mixed::get, false))
                .hasMessageContaining("nexuspay.hyperswitch.webhook-secret")
                .hasMessageContaining("nexuspay.vault.encryption.master-key")
                .satisfies(ex -> {
                    if (ex.getMessage().contains("nexuspay.session.jwt-secret")) {
                        throw new AssertionError("managed key must not be flagged");
                    }
                });
    }
}
