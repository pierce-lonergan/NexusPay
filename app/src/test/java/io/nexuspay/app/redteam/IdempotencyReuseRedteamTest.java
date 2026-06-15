package io.nexuspay.app.redteam;

import io.nexuspay.app.IntegrationTestBase;
import io.nexuspay.app.config.TestSecurityConfig;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * RED-TEAM (report-only, {@code @Tag("redteam")}): missing server-derived
 * idempotency key allows a double-charge (B-012).
 *
 * <p><strong>Attack / fault:</strong> submit TWO identical
 * {@code POST /v1/payments} with NO {@code Idempotency-Key} header. A SECURE system
 * derives a stable server-side key (or rejects the keyless mutation) so the
 * duplicate is deduped at the PSP and only ONE charge is created. On current main
 * the key is optional and no server-side key is derived, so two identical requests
 * mint two distinct charges (double-charge).</p>
 *
 * <p><strong>Why this is {@code @Disabled}, not asserting:</strong> demonstrating
 * the double-charge requires a PSP that mints a DISTINCT successful payment id per
 * call, so two keyless requests visibly produce two different charges. The {@code app}
 * integration harness ({@link IntegrationTestBase}) has NO PSP stub — there is no
 * WireMock server wired in (the only WireMock mappings live under the
 * {@code payment-orchestration} module tests), and {@code application-test.yml}
 * points {@code nexuspay.hyperswitch.base-url} at a non-running port. So every
 * {@code POST /v1/payments} fails downstream at the PSP (422/5xx) regardless of the
 * idempotency key, and BOTH the vulnerable and the fixed code return the same
 * failure — the test cannot distinguish them. (The previous version compared two
 * 422 error bodies, which always matched → it was GREEN on the vulnerable main =
 * false assurance. That unsound extract-on-error-fallback logic has been removed.)
 *
 * <p>TODO(SEC-BATCH-5): wire a stub PSP (WireMock) into the app harness that returns
 * a distinct successful payment id per {@code POST /payments} so two keyless
 * requests demonstrably create two charges on main; then assert the two responses
 * carry DISTINCT ids on main and the SAME id once B-012 derives a server-side
 * idempotency key. Enable this test in that PR.</p>
 */
@Tag("redteam")
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@Disabled("B-012/SEC-BATCH-5: needs a stub PSP minting distinct ids to demonstrate double-charge — enable when that lands")
@DisplayName("RED-TEAM: idempotency-key reuse / absence → double-charge (B-012)")
class IdempotencyReuseRedteamTest extends IntegrationTestBase {

    @Test
    @DisplayName("a mutating payment with NO idempotency key must not silently create two charges")
    void noIdempotencyKey_mustNotDoubleCharge() {
        // Intentionally empty: see the @Disabled reason / class javadoc. A genuine
        // fail-on-main assertion needs a stub PSP minting distinct payment ids, which
        // the app harness does not yet provide. Asserting anything here against the
        // current PSP-less harness would compare two identical downstream failures and
        // pass vacuously on the vulnerable code (false assurance) — so we assert nothing
        // until the stub PSP lands.
    }
}
