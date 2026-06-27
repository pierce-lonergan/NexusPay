package io.nexuspay.payment.adapter.out.mock;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * TEST-3b: canonical TEST-mode saved-payment-method fixture registry — the single source of truth for
 * the Stripe-style fixture tokens an integrator may attach under a {@code sk_test_} key. Sibling to
 * {@link MockPaymentGatewayPort} (same {@code adapter.out.mock} package), keeping the test-fixture
 * concern next to the mock PSP and introducing NO new modulith edge.
 *
 * <h3>Why fixtures (SEC-BATCH-3 / PCI)</h3>
 * <p>A saved method must NEVER carry a raw PAN. In TEST mode the integrator instead supplies a fixture
 * token (e.g. {@code pm_card_visa}); {@link #resolve(String)} maps it to CANNED display fields
 * (brand/last4/exp/funding) PLUS a SYNTHETIC opaque {@code credentialRef} (e.g.
 * {@code pmref_test_pm_card_visa}) that the later 3c off-session charge resolves through the mock. No PAN
 * is ever supplied or parsed.</p>
 *
 * <p>The fixture path is HARD-GATED on {@code CallerMode.isTest()} at the service boundary: a fixture
 * token under a LIVE key is a 400 (request-validation), not a hidden-route 404.</p>
 *
 * <p>Mirrors {@code MockPaymentGatewayPort}'s {@code ForcedOutcome} enum + public
 * {@code FORCED_PAYMENT_OUTCOMES} set so the catalog doc + tests have one authority.</p>
 *
 * @since TEST-3b
 */
public final class TestPaymentMethodFixtures {

    private TestPaymentMethodFixtures() {
        // Utility / registry class
    }

    /** Every fixture token starts with this prefix; {@link #isFixture(String)} keys off it. */
    public static final String FIXTURE_PREFIX = "pm_card_";

    /** The synthetic opaque credential_ref prefix the future 3c off-session charge resolves through the mock. */
    public static final String SYNTHETIC_REF_PREFIX = "pmref_test_";

    /**
     * Canned display fields + a synthetic opaque {@code credentialRef} a fixture token resolves to. NO PAN
     * — only the display surface a saved method legitimately exposes plus the chargeable handle.
     */
    public record FixtureCard(
            String brand,
            String last4,
            Integer expMonth,
            Integer expYear,
            String funding,
            String credentialRef
    ) {}

    // The canonical registry. The synthetic credentialRef is SYNTHETIC_REF_PREFIX + token (3c resolves it).
    private static final Map<String, FixtureCard> REGISTRY;

    static {
        Map<String, FixtureCard> r = new LinkedHashMap<>();
        r.put("pm_card_visa", card("visa", "4242"));
        r.put("pm_card_mastercard", card("mastercard", "4444"));
        r.put("pm_card_amex", card("amex", "0005"));
        // A SAVEABLE method whose synthetic credential_ref encodes a decline for 3c's off-session charge to
        // honor — the ATTACH itself still succeeds (the method is valid; only a future charge declines).
        r.put("pm_card_chargeDeclined", card("visa", "0002"));
        REGISTRY = Map.copyOf(r);
    }

    private static FixtureCard card(String brand, String last4) {
        // exp 12/2034, funding credit are the canned defaults for every fixture; credentialRef is filled
        // per-token in resolve() so the synthetic handle encodes the originating fixture.
        return new FixtureCard(brand, last4, 12, 2034, "credit", null);
    }

    /**
     * TEST-3b: the canonical fixture tokens — exposed for the catalog doc + tests (single source of
     * truth). Mirrors {@code MockPaymentGatewayPort.FORCED_PAYMENT_OUTCOMES}.
     */
    public static final Set<String> FIXTURE_TOKENS = REGISTRY.keySet();

    /**
     * @return {@code true} iff {@code token} is shaped like a TEST-mode fixture token (starts with
     *         {@code pm_card_}). A {@code true} here means the fixture PATH applies (which is hard-gated on
     *         {@code CallerMode.isTest()}); an UNKNOWN {@code pm_card_*} still returns {@code true} so the
     *         service can reject it as a 400 rather than silently storing it (see {@link #resolve}).
     */
    public static boolean isFixture(String token) {
        return token != null && token.startsWith(FIXTURE_PREFIX);
    }

    /**
     * Resolves a known fixture token to its canned display fields + a synthetic opaque
     * {@code credentialRef} ({@code pmref_test_<token>}). Returns {@link Optional#empty()} for an unknown
     * {@code pm_card_*} token (the service treats that as a 400 — never silently stores an unknown fixture).
     */
    public static Optional<FixtureCard> resolve(String token) {
        FixtureCard base = REGISTRY.get(token);
        if (base == null) {
            return Optional.empty();
        }
        return Optional.of(new FixtureCard(
                base.brand(), base.last4(), base.expMonth(), base.expYear(), base.funding(),
                SYNTHETIC_REF_PREFIX + token));
    }
}
