package io.nexuspay.payment.adapter.out.mock;

import io.nexuspay.payment.adapter.out.mock.TestPaymentMethodFixtures.FixtureCard;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEST-3b: pins the canonical {@link TestPaymentMethodFixtures} registry — the single source of truth for
 * the TEST-mode fixture tokens. Mirrors {@code MockPaymentGatewayPortTest}'s coverage of
 * {@code FORCED_PAYMENT_OUTCOMES}.
 */
class TestPaymentMethodFixturesTest {

    @Test
    void isFixture_keysOffThePmCardPrefix() {
        assertThat(TestPaymentMethodFixtures.isFixture("pm_card_visa")).isTrue();
        assertThat(TestPaymentMethodFixtures.isFixture("pm_card_anything")).isTrue(); // shape, not membership
        assertThat(TestPaymentMethodFixtures.isFixture("ptok_live_x")).isFalse();
        assertThat(TestPaymentMethodFixtures.isFixture(null)).isFalse();
    }

    @Test
    void resolveVisa_yieldsCannedDisplay_andSyntheticOpaqueRef() {
        Optional<FixtureCard> resolved = TestPaymentMethodFixtures.resolve("pm_card_visa");

        assertThat(resolved).isPresent();
        FixtureCard card = resolved.get();
        assertThat(card.brand()).isEqualTo("visa");
        assertThat(card.last4()).isEqualTo("4242");
        assertThat(card.expMonth()).isEqualTo(12);
        assertThat(card.expYear()).isEqualTo(2034);
        assertThat(card.funding()).isEqualTo("credit");
        // a SYNTHETIC opaque handle (no PAN), what 3c later resolves through the mock.
        assertThat(card.credentialRef()).isEqualTo("pmref_test_pm_card_visa");
        assertThat(card.credentialRef()).doesNotContainPattern("\\d{13,19}");
    }

    @Test
    void resolveChargeDeclined_isSaveable_withItsOwnSyntheticRef() {
        Optional<FixtureCard> resolved = TestPaymentMethodFixtures.resolve("pm_card_chargeDeclined");
        assertThat(resolved).isPresent();
        assertThat(resolved.get().last4()).isEqualTo("0002");
        assertThat(resolved.get().credentialRef()).isEqualTo("pmref_test_pm_card_chargeDeclined");
    }

    @Test
    void resolveUnknownPmCardToken_isEmpty() {
        // an unknown pm_card_* is NOT silently storable — empty so the service rejects it 400.
        assertThat(TestPaymentMethodFixtures.resolve("pm_card_nope")).isEmpty();
    }

    @Test
    void fixtureTokens_containExactlyTheFourCanonicalTokens() {
        assertThat(TestPaymentMethodFixtures.FIXTURE_TOKENS).containsExactlyInAnyOrder(
                "pm_card_visa", "pm_card_mastercard", "pm_card_amex", "pm_card_chargeDeclined");
    }
}
