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
 * RED-TEAM (report-only, {@code @Tag("redteam")}): payment-lifecycle IDOR (B-007).
 *
 * <p><strong>Attack:</strong> an attacker tenant tries to GET / capture / cancel a
 * payment id that belongs to a DIFFERENT tenant, and to issue a sub-threshold
 * cross-tenant refund (amount {@code 49999} routes direct-to-PSP, bypassing the
 * maker-checker approval). A SECURE system scopes every payment-by-id operation to
 * the authenticated principal's tenant and returns 404 for another tenant's
 * payment.</p>
 *
 * <p><strong>Why this is {@code @Disabled}, not asserting:</strong> the payment
 * lifecycle is entirely PSP-backed. {@code PaymentController.getPayment/capture/
 * cancel/refund} delegate straight through {@code GatedPaymentGateway} to
 * {@code HyperSwitchPaymentAdapter}, which makes a REST call to HyperSwitch — there
 * is NO local payments table to seed a victim-owned payment into. The {@code app}
 * integration harness ({@link IntegrationTestBase}) has no PSP stub
 * (no WireMock; {@code application-test.yml} points the base-url at a non-running
 * port), so any lifecycle call on a victim id fails downstream at the PSP
 * (RestClientException → {@code PaymentException.gatewayError} → 5xx/422) on BOTH
 * the vulnerable and the fixed code — there is no observable 200 leak to distinguish
 * them, and no way to prove the seeded payment really belongs to the victim.
 *
 * <p>The previous version used unseeded ids ({@code pay_victim_owned_lifecycle}) and
 * asserted 404/403, which "failed on main" only as an unscoped not-found/gateway
 * error, NOT as a proven cross-tenant leak — a fix that merely mapped not-found→404
 * would have greened it without proving isolation (false assurance). That unsound
 * assertion has been removed.
 *
 * <p>TODO(B-007/SEC-BATCH-3): once the app harness has a stub PSP (WireMock) able to
 * (a) mint a payment owned by the victim tenant and (b) return it on a subsequent
 * GET/capture/cancel/refund, enable this and assert: attacker (spoofed tenant) gets
 * 404 WHILE a same-tenant control read of that payment succeeds — mirroring the
 * seed-then-attack pattern in {@code CrossTenantIdorRedteamTest}. The
 * ScreeningOriginService gatewayPaymentId→(tenant,mode) mapping is the natural
 * ownership oracle for the SEC fix.</p>
 */
@Tag("redteam")
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@Disabled("B-007/SEC-BATCH-3: payment lifecycle is PSP-backed (no local payment to seed); needs a stub PSP to mint+return a victim-owned payment to prove the leak — enable when that lands")
@DisplayName("RED-TEAM: payment-lifecycle cross-tenant IDOR (B-007)")
class PaymentLifecycleIdorRedteamTest extends IntegrationTestBase {

    @Test
    @DisplayName("attacker cannot GET/capture/cancel/refund another tenant's payment")
    void crossTenantPaymentLifecycle_isRefused() {
        // Intentionally empty: see the @Disabled reason / class javadoc. A genuine
        // fail-on-main assertion needs a stub PSP that mints a victim-owned payment and
        // returns it, so the attacker's request demonstrably leaks it (200) on main and
        // is refused (404) once B-007 scopes by tenant. The current PSP-less harness can
        // only produce a gateway error for any id, which does not distinguish vulnerable
        // from fixed — so we assert nothing until the stub PSP lands.
    }
}
