package io.nexuspay.app.redteam;

import io.nexuspay.app.IntegrationTestBase;
import io.nexuspay.app.config.TestSecurityConfig;
import io.nexuspay.b2b.application.port.out.B2bRepository;
import io.nexuspay.b2b.domain.VirtualCard;
import io.nexuspay.b2b.domain.VirtualCardType;
import io.nexuspay.marketplace.application.port.out.MarketplaceRepository;
import io.nexuspay.marketplace.domain.ConnectedAccount;
import io.nexuspay.marketplace.domain.KycStatus;
import io.nexuspay.marketplace.domain.Payout;
import io.nexuspay.marketplace.domain.PayoutMethod;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RED-TEAM (report-only, {@code @Tag("redteam")}): cross-tenant IDOR via the
 * client-supplied {@code X-Tenant-Id} header (B-002 / B-005 / B-006).
 *
 * <p><strong>Attack:</strong> authenticate as {@code attackerTenant} (a valid
 * admin in their OWN tenant) but send {@code X-Tenant-Id: victimTenant} to read or
 * act on another tenant's resources — vaulted card, payout, virtual card. A SECURE
 * system derives the effective tenant from the authenticated PRINCIPAL, ignores the
 * header, and returns 404 for a resource that does not belong to the caller's
 * tenant.</p>
 *
 * <p><strong>Soundness — every victim resource is really SEEDED.</strong> Each
 * attack first creates a REAL resource owned by {@code victimTenant} (via the
 * domain repository, mirroring {@code PayoutDoublePayRedteamTest}'s seeding and the
 * vaulted-card test below). The assertion then has TWO sides:</p>
 * <ul>
 *   <li>the ATTACKER (authenticated as {@code attackerTenant}, spoofing
 *       {@code X-Tenant-Id: victimTenant}) must be REFUSED (404) — proving the
 *       boundary, not a not-found accident, and</li>
 *   <li>a CONTROL read of the SAME id as the victim succeeds (200) — proving the
 *       resource really exists, so the attacker's 404 is the boundary refusing a
 *       real resource, not a missing row.</li>
 * </ul>
 * On current main the by-id loads ignore tenant entirely
 * ({@code PayoutService.getPayout}/{@code VirtualCardService.getCard} drop their
 * {@code tenantId} arg), so the attacker read returns the victim's resource (200) →
 * the attacker-side 404 assertion FAILS on main = the leak is proven. A fix that
 * merely maps not-found→404 would NOT green this, because the seeded resource is
 * present; only a real ownership check makes the attacker 404 while the control 200.
 *
 * <p><strong>Why this FAILS on current main (hence excluded + report-only):</strong>
 * the controllers take {@code @RequestHeader("X-Tenant-Id")} verbatim and the
 * services ignore ownership; the app datasource authenticates as the table OWNER so
 * RLS does not back-stop this (per {@code RlsIsolationIntegrationTest}) — the
 * assertion is at the HTTP/app layer. When the SEC fix lands (controllers derive
 * tenant from the principal, by-id loads assert ownership), drop
 * {@code @Tag("redteam")} to gate this.</p>
 */
@Tag("redteam")
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@DisplayName("RED-TEAM: cross-tenant IDOR via X-Tenant-Id (B-002/B-005/B-006)")
class CrossTenantIdorRedteamTest extends IntegrationTestBase {

    private static final String ATTACKER_TENANT = "attackerTenant";
    private static final String VICTIM_TENANT = "victimTenant";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MarketplaceRepository marketplaceRepository;

    @Autowired
    private B2bRepository b2bRepository;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DOCKER_AVAILABLE,
                "Docker unavailable — red-team IDOR scenario self-skips (Testcontainers required)");
    }

    @Test
    @DisplayName("attacker cannot read a VICTIM tenant's vaulted card via X-Tenant-Id")
    void vaultedCard_crossTenantRead_isRefused() throws Exception {
        // Victim vaults a card under their own tenant.
        String victimToken = vaultCardFor(VICTIM_TENANT);

        // Control: the victim CAN read their own card (proves the resource exists).
        mockMvc.perform(get("/v1/vault/cards/{token}", victimToken)
                        .header("X-Tenant-Id", VICTIM_TENANT)
                        .with(authentication(TestSecurityConfig.authFor(VICTIM_TENANT, "admin"))))
                .andExpect(status().isOk());

        // Attacker (admin in attackerTenant) tries to read it by spoofing X-Tenant-Id.
        mockMvc.perform(get("/v1/vault/cards/{token}", victimToken)
                        .header("X-Tenant-Id", VICTIM_TENANT)
                        .with(authentication(TestSecurityConfig.authFor(ATTACKER_TENANT, "admin"))))
                // SECURE: the effective tenant comes from the principal (attackerTenant),
                // the card belongs to victimTenant → not found for this caller.
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("attacker cannot read a VICTIM tenant's payout via X-Tenant-Id")
    void payout_crossTenantRead_isRefused() throws Exception {
        // Seed a REAL payout owned by the victim tenant.
        String victimAccountId = seedActiveAccount(VICTIM_TENANT);
        Payout seeded = Payout.create(victimAccountId, VICTIM_TENANT, 100_000L, "USD", PayoutMethod.BANK_TRANSFER);
        String payoutId = marketplaceRepository.savePayout(seeded).getId();

        // Control: the victim CAN read their own payout (proves it exists).
        mockMvc.perform(get("/v1/payouts/{payoutId}", payoutId)
                        .header("X-Tenant-Id", VICTIM_TENANT)
                        .with(authentication(TestSecurityConfig.authFor(VICTIM_TENANT, "admin"))))
                .andExpect(status().isOk());

        // Attacker spoofs X-Tenant-Id → a secure by-id load scopes to the PRINCIPAL's
        // tenant (attackerTenant) and returns 404. On main getPayout ignores tenant → 200 (leak).
        mockMvc.perform(get("/v1/payouts/{payoutId}", payoutId)
                        .header("X-Tenant-Id", VICTIM_TENANT)
                        .with(authentication(TestSecurityConfig.authFor(ATTACKER_TENANT, "admin"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("attacker cannot read a VICTIM tenant's virtual card via X-Tenant-Id")
    void virtualCard_crossTenantRead_isRefused() throws Exception {
        // Seed a REAL virtual card owned by the victim tenant.
        VirtualCard card = VirtualCard.create(
                VICTIM_TENANT, "stub-issuer", VirtualCardType.MULTI_USE,
                500_000L, "USD", Instant.now().plus(90, ChronoUnit.DAYS));
        card.setCardLast4("4242");
        String cardId = b2bRepository.saveVirtualCard(card).getId();

        // Control: the victim CAN read their own card (proves it exists).
        mockMvc.perform(get("/v1/virtual-cards/{cardId}", cardId)
                        .header("X-Tenant-Id", VICTIM_TENANT)
                        .with(authentication(TestSecurityConfig.authFor(VICTIM_TENANT, "admin"))))
                .andExpect(status().isOk());

        // Attacker spoofs X-Tenant-Id → secure getCard scopes to attackerTenant → 404.
        // On main getCard ignores tenant → 200 (leak).
        mockMvc.perform(get("/v1/virtual-cards/{cardId}", cardId)
                        .header("X-Tenant-Id", VICTIM_TENANT)
                        .with(authentication(TestSecurityConfig.authFor(ATTACKER_TENANT, "admin"))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("attacker cannot create a payout against a VICTIM tenant's connected account")
    void payout_crossTenantCreate_isRefused() throws Exception {
        // Seed a REAL, ACTIVE (payout-eligible) connected account owned by the victim.
        String victimAccountId = seedActiveAccount(VICTIM_TENANT);

        String body = """
                {
                  "connectedAccountId": "%s",
                  "amount": 100000,
                  "currency": "USD",
                  "method": "BANK_TRANSFER"
                }
                """.formatted(victimAccountId);

        // Control: the OWNER (victim) CAN create a payout against their own account (proves
        // the account is real and payout-eligible).
        mockMvc.perform(post("/v1/payouts")
                        .header("X-Tenant-Id", VICTIM_TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(authentication(TestSecurityConfig.authFor(VICTIM_TENANT, "admin"))))
                .andExpect(status().isCreated());

        // Attacker spoofs X-Tenant-Id. createPayout must assert account ownership against
        // the PRINCIPAL's tenant → 404 (account not visible) or 403 (forbidden). On main
        // ownership is never checked → 201 (the victim's account is paid out by the attacker).
        mockMvc.perform(post("/v1/payouts")
                        .header("X-Tenant-Id", VICTIM_TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(authentication(TestSecurityConfig.authFor(ATTACKER_TENANT, "admin"))))
                .andExpect(result -> {
                    int sc = result.getResponse().getStatus();
                    org.assertj.core.api.Assertions.assertThat(sc)
                            .as("cross-tenant payout create must be refused (404/403), not 201/202")
                            .isIn(403, 404);
                });
    }

    /** Seeds an ACTIVE (KYC-verified) connected account for the tenant and returns its id. */
    private String seedActiveAccount(String tenant) {
        ConnectedAccount account = ConnectedAccount.create(
                tenant, "Victim LLC", "victim@example.com", "US", "USD");
        account.updateKycStatus(KycStatus.VERIFIED);
        account.activate();
        return marketplaceRepository.saveAccount(account).getId();
    }

    /** Vaults a card under the given tenant and returns its vault token. */
    private String vaultCardFor(String tenant) throws Exception {
        var res = mockMvc.perform(post("/v1/vault/cards")
                        .header("X-Tenant-Id", tenant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pan": "4111111111111111",
                                  "expMonth": 12,
                                  "expYear": 2030,
                                  "cardholderName": "Victim Owner"
                                }
                                """)
                        .with(authentication(TestSecurityConfig.authFor(tenant, "admin"))))
                .andReturn();
        String body = res.getResponse().getContentAsString();
        // VaultCardResponse exposes the vault token under "token".
        return com.jayway.jsonpath.JsonPath.read(body, "$.token");
    }
}
