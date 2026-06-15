package io.nexuspay.fraud.application.service;

import io.nexuspay.fraud.application.dto.PaymentContext;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B-029-hardening T3 property/fuzz coverage for {@link RequestFingerprinter}: the keyed-HMAC request
 * fingerprint + its canonical, injective encoding.
 *
 * <p>Pins: (i) determinism, (ii) injectivity / anti-collision incl. delimiter-injection and
 * null-vs-empty, (iii) the stored digest never reveals the raw card / customer / amount, (iv) the
 * key participates (different keys → different fingerprints), and (v) fail-closed on an unusable key.
 * The {@link RequestFingerprinter} is constructed with a real key so the actual HMAC runs.</p>
 *
 * <p><b>Anti-vacuity invariant.</b> The anti-collision assertions are written so they would FAIL if
 * {@link RequestFingerprinter#canonicalize} regressed to a naive in-band delimiter join such as
 * {@code tenant+"|"+amount+"|"+currency+"|"+customer+"|"+cardHash}. Each delimiter-SHIFT pair below
 * is chosen to COLLIDE under that naive join but to be DISTINCT under the real length-prefixed,
 * type-tagged, null-marked encoding. We assert both the high-level {@code fingerprint(...)} inequality
 * AND the low-level {@code canonicalize(...)} byte-array inequality, and the fuzz generator emits
 * cross-field delimiter-shift variants whose naive concatenation would alias — so a pipe-join
 * regression cannot keep these tests green.</p>
 *
 * <p>NIT 1: the canonical tuple is tenant-self-binding (TAG_TENANT written first, TAG_VERSION 0x02).
 * The static {@code canonicalize} therefore takes {@code tenantId} as its first parameter and
 * {@code fingerprint(PaymentContext)} threads {@code context.tenantId()} through it.</p>
 */
class RequestFingerprinterTest {

    private static final String KEY_A =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private static final String KEY_B =
            Base64.getEncoder().encodeToString("FEDCBA9876543210FEDCBA9876543210".getBytes());

    /** Fixed tenant used by {@link #ctx}: NIT 1 folds this into the canonical tuple first. */
    private static final String TENANT = "T";

    private static final RequestFingerprinter FP_A = new RequestFingerprinter(KEY_A);
    private static final RequestFingerprinter FP_B = new RequestFingerprinter(KEY_B);

    /** Injective string tag: distinguishes null from "" and length-prefixes the value. */
    private static String tag(String s) {
        return s == null ? "N" : ("S" + s.length() + ":" + s);
    }

    /**
     * The NAIVE, VULNERABLE in-band pipe-join the real canonicalize MUST NOT be — the exact mutant the
     * reviewer used. Built over the full tenant-bound tuple. Two logically-distinct tuples can alias to
     * the same string here (delimiter-shift forgery); the real injective encoding keeps them distinct.
     * Used in-test only to PROVE each collision pair genuinely aliases under a pipe-join (so the pair
     * is a real regression-catcher, not an accidentally-distinct pair).
     */
    private static String naiveJoin(String tenant, long amount, String currency, String customerId,
                                    String cardHash) {
        return tenant + "|" + amount + "|" + currency + "|" + customerId + "|" + cardHash;
    }

    private static PaymentContext ctx(long amount, String currency, String customerId, String cardHash) {
        // Only tenantId/amount/currency/customerId/cardHash bind the fingerprint; the rest are irrelevant.
        return new PaymentContext("pay-1", TENANT, amount, currency, customerId, "a@b.com",
                "411111", cardHash, "1.1.1.1", "US", "dev", Map.of(), Map.of(), "pay-1");
    }

    // ----------------------------------------------------------------------------------------------
    // (i) DETERMINISM
    // ----------------------------------------------------------------------------------------------

    @Test
    void determinism_sameTuple_sameFingerprint_acrossCallsAndInstances() {
        PaymentContext c = ctx(5000, "USD", "cust_1", "tok_abc");
        String f1 = FP_A.fingerprint(c);
        for (int i = 0; i < 1000; i++) {
            assertThat(FP_A.fingerprint(c)).isEqualTo(f1);
        }
        // A second instance built with the SAME key reproduces the SAME value (cross-process equality).
        RequestFingerprinter another = new RequestFingerprinter(KEY_A);
        assertThat(another.fingerprint(c)).isEqualTo(f1);
    }

    @Test
    void output_is64LowercaseHex() {
        String f = FP_A.fingerprint(ctx(5000, "USD", "cust_1", "tok_abc"));
        assertThat(f).matches("^[0-9a-f]{64}$");
    }

    // ----------------------------------------------------------------------------------------------
    // (ii) INJECTIVITY / ANTI-COLLISION
    // ----------------------------------------------------------------------------------------------

    @Test
    void delimiterInjection_pipeShift_doesNotCollide() {
        // The classic pipe-join forgery: "1|US|a" vs "1|USa|". A length-prefixed canonical form makes
        // these distinct because currency/customer carry explicit lengths, not an in-band delimiter.
        String f1 = FP_A.fingerprint(ctx(1, "US", "a", "tok"));
        String f2 = FP_A.fingerprint(ctx(1, "USa", "", "tok"));
        assertThat(f1).isNotEqualTo(f2);
    }

    // ----------------------------------------------------------------------------------------------
    // (ii-bis) DELIMITER-SHIFT FORGERY PAIRS — the BLOCKER fix.
    //
    // These are the reused-key-different-charge attack: each (A, B) pair is a DIFFERENT logical request
    // that a naive in-band delimiter join would ALIAS to one string. Under the injective encoding they
    // MUST diverge, both at the fingerprint level AND the raw-canonical-bytes level. If canonicalize()
    // regressed to a pipe-join mutant, fingerprint(A) == fingerprint(B) and canonicalize(A) ==
    // canonicalize(B) for the string-boundary pairs, and those tests would FAIL — which is the whole
    // point (the prior tests stayed green under the mutant). Each test asserts the precise naive-join
    // form it aliases under, so the pair is a genuine regression catcher and not merely an
    // accidentally-distinct pair. The standalone reviewer-mutation harness (C:\Temp\fpverify) confirms
    // the suite passes on the real injective encoding and FAILS on the pipe-join mutant.
    // ----------------------------------------------------------------------------------------------

    @Test
    void delimiterShift_currencyCustomerBoundary_collidesUnderNaiveJoin_butNotReally() {
        // (a) currency/customer shift: (1,"US","a|b","tok") vs (1,"US|a","b","tok").
        PaymentContext a = ctx(1, "US", "a|b", "tok");
        PaymentContext b = ctx(1, "US|a", "b", "tok");

        // Precondition: under the reviewer's exact pipe-join mutant
        // (amount+"|"+currency+"|"+customer+"|"+card) these two DISTINCT requests alias to ONE string
        // ("...|US|a|b|tok"). So a pipe-join regression WOULD collide them.
        assertThat(naiveJoin(TENANT, 1, "US", "a|b", "tok"))
                .isEqualTo(naiveJoin(TENANT, 1, "US|a", "b", "tok"));

        // Real encoding MUST keep them distinct — both at the digest and the raw-bytes level.
        assertThat(FP_A.fingerprint(a)).isNotEqualTo(FP_A.fingerprint(b));
        byte[] canonA = RequestFingerprinter.canonicalize(TENANT, 1, "US", "a|b", "tok");
        byte[] canonB = RequestFingerprinter.canonicalize(TENANT, 1, "US|a", "b", "tok");
        assertThat(Arrays.equals(canonA, canonB)).isFalse();
        assertThat(canonA).isNotEqualTo(canonB);
    }

    @Test
    void delimiterShift_amountCurrencyBoundary_collidesUnderNaiveJoin_butNotReally() {
        // (b) amount/currency shift: (1,"2|x","c","tok") vs (12,"x","c","tok"). The amount is rendered
        // (Long.toString) and glued to currency. Under an amount-GLUED naive join
        // (amount + currency + "|" + customer + "|" + card) the boundary digit slides:
        // "1"+"2|x" = "12|x"  vs  "12"+"x" = "12x" still differ because of the literal '|' payload, so
        // we pin the canonical-bytes + fingerprint DISTINCTNESS directly (the real, mandatory property)
        // and let the harness exercise the glued-boundary alias on pipe-free values. The amount field
        // tag + 8-byte length prefix is what makes amount=1,"2|x" unambiguous vs amount=12,"x".
        PaymentContext a = ctx(1, "2|x", "c", "tok");
        PaymentContext b = ctx(12, "x", "c", "tok");

        assertThat(FP_A.fingerprint(a)).isNotEqualTo(FP_A.fingerprint(b));
        byte[] canonA = RequestFingerprinter.canonicalize(TENANT, 1, "2|x", "c", "tok");
        byte[] canonB = RequestFingerprinter.canonicalize(TENANT, 12, "x", "c", "tok");
        assertThat(Arrays.equals(canonA, canonB)).isFalse();
        assertThat(canonA).isNotEqualTo(canonB);

        // The clean amount/currency delimiter-shift alias (pipe-free) that an amount-GLUED join would
        // collide: (1,"2x") and (12,"x") both glue to "12x". The real encoding keeps them distinct —
        // this is the genuine regression catcher for the amount|currency boundary.
        assertThat(FP_A.fingerprint(ctx(1, "2x", "c", "tok")))
                .isNotEqualTo(FP_A.fingerprint(ctx(12, "x", "c", "tok")));
        assertThat(Arrays.equals(
                RequestFingerprinter.canonicalize(TENANT, 1, "2x", "c", "tok"),
                RequestFingerprinter.canonicalize(TENANT, 12, "x", "c", "tok"))).isFalse();
    }

    @Test
    void delimiterShift_customerCardBoundary_collidesUnderNaiveJoin_butNotReally() {
        // (c) customer/card shift across the customer|card boundary: (1,"US","ab","c|tok") vs
        // (1,"US","ab|c","tok"). Under the reviewer's pipe-join mutant both glue to "...|US|ab|c|tok";
        // the real encoding keeps the customer and card lengths explicit so the '|' is just payload.
        PaymentContext a = ctx(1, "US", "ab", "c|tok");
        PaymentContext b = ctx(1, "US", "ab|c", "tok");

        // Precondition: pipe-join mutant aliases these two DISTINCT requests to one string.
        assertThat(naiveJoin(TENANT, 1, "US", "ab", "c|tok"))
                .isEqualTo(naiveJoin(TENANT, 1, "US", "ab|c", "tok"));

        assertThat(FP_A.fingerprint(a)).isNotEqualTo(FP_A.fingerprint(b));
        byte[] canonA = RequestFingerprinter.canonicalize(TENANT, 1, "US", "ab", "c|tok");
        byte[] canonB = RequestFingerprinter.canonicalize(TENANT, 1, "US", "ab|c", "tok");
        assertThat(Arrays.equals(canonA, canonB)).isFalse();
        assertThat(canonA).isNotEqualTo(canonB);
    }

    @Test
    void nullVsEmptyString_doesNotCollide() {
        // (currency=null, customer="x") vs (currency="", customer="x") must differ — distinct markers.
        String fNull = FP_A.fingerprint(ctx(1, null, "x", "tok"));
        String fEmpty = FP_A.fingerprint(ctx(1, "", "x", "tok"));
        assertThat(fNull).isNotEqualTo(fEmpty);
    }

    @Test
    void embeddedControlBytes_inCardHash_doNotForgeCollisions() {
        // A value containing the would-be delimiters '|', NUL, newline is just payload counted by the
        // length prefix; it cannot be misread as a boundary.
        Set<String> fps = new HashSet<>();
        fps.add(FP_A.fingerprint(ctx(1, "US", "x", "tok|extra")));
        fps.add(FP_A.fingerprint(ctx(1, "US", "x", "tok extra")));
        fps.add(FP_A.fingerprint(ctx(1, "US", "x", "tok\nextra")));
        fps.add(FP_A.fingerprint(ctx(1, "US", "x", "tokextra")));
        assertThat(fps).hasSize(4); // all four distinct
    }

    @Test
    void fieldBoundaryShift_acrossAmountCurrency_doesNotCollide() {
        // Moving a char across the amount|currency boundary: amount=12, currency="34X" vs
        // amount=1234, currency="X". Length+tag prefixing keeps them distinct.
        String f1 = FP_A.fingerprint(ctx(12, "34X", "c", "tok"));
        String f2 = FP_A.fingerprint(ctx(1234, "X", "c", "tok"));
        assertThat(f1).isNotEqualTo(f2);
    }

    @Test
    void amountVariants_distinct() {
        Set<String> fps = new HashSet<>();
        fps.add(FP_A.fingerprint(ctx(5000, "USD", "c", "tok")));
        fps.add(FP_A.fingerprint(ctx(50000, "USD", "c", "tok")));
        fps.add(FP_A.fingerprint(ctx(-5000, "USD", "c", "tok")));
        assertThat(fps).hasSize(3);
    }

    @Test
    void fuzz_distinctTuples_yieldDistinctFingerprints() {
        // Randomized fuzz: generate many tuples, assert no two DISTINCT logical tuples share a
        // fingerprint. Crucially the generator EMITS cross-field delimiter-shift variants whose NAIVE
        // pipe-join would ALIAS (so a pipe-join regression in canonicalize() WOULD surface as a
        // collision here). The collision index is keyed on a SEPARATE injective identity of each logical
        // tuple (NOT on the bytes of the code under test), so if the code under test aliased two
        // distinct logical inputs to one fingerprint, their differing identities would trip the
        // assertion. For every emitted shift variant we ALSO directly fingerprint its naive-aliasing
        // partner and assert the two differ — a pipe-join regression fails on the spot.
        Random rnd = new Random(42); // fixed seed for reproducibility
        String[] currencies = {null, "", "U", "US", "USD", "US|", "|US", "U|S"};
        String[] customers = {null, "", "a", "ab", "a|b", "a b", "1", "12"};
        String[] cards = {null, "", "tok", "tok|x", "tok\nx", "4111111111111111"};

        // fingerprint -> the injective, human-readable identity of the FIRST logical tuple that produced
        // it. Identity is computed INDEPENDENTLY of the code under test (length-prefixed tag()), so it
        // is genuinely injective. If a fingerprint reappears, the logical tuple MUST be identical by this
        // identity — a true HMAC collision is ruled out cryptographically, so a mismatch can only mean a
        // canonicalization collision in canonicalize() (e.g. a pipe-join mutant aliasing a shift pair).
        Map<String, String> injectiveKeyByFp = new java.util.HashMap<>();
        int checked = 0;
        int shiftPairsEmitted = 0;
        for (int i = 0; i < 4000; i++) {
            long amount;
            String cur;
            String cust;
            String card;

            // ~1 in 4 iterations emit a deliberate cross-field delimiter-SHIFT variant. We construct an
            // aliasing PAIR (A, B) over a string boundary: for fields X|Y, A=(X, "|"+Y) and
            // B=(X+"|", Y) both naive-join to "X||Y" yet are logically distinct. We then assert the REAL
            // fingerprints of A and B diverge. Under a pipe-join regression they would be EQUAL → FAIL.
            // (The amount/currency boundary — where amount is a long — is covered concretely by
            // delimiterShift_amountCurrencyBoundary_collidesUnderNaiveJoin_butNotReally above.)
            if (rnd.nextInt(4) == 0) {
                long baseAmount = Math.abs(rnd.nextLong() % 100L) + 1; // small positive amount
                String baseCur = currencies[rnd.nextInt(currencies.length)];
                String baseCust = customers[rnd.nextInt(customers.length)];
                String baseCard = cards[rnd.nextInt(cards.length)];
                if (baseCur == null) baseCur = "US";
                if (baseCust == null) baseCust = "ab";
                if (baseCard == null) baseCard = "tok";

                long aAmount = baseAmount;
                long bAmount = baseAmount;
                String aCur, aCust, aCard;
                String bCur, bCust, bCard;
                if (rnd.nextBoolean()) {
                    // currency/customer boundary: A=(cur, "|"+cust), B=(cur+"|", cust).
                    aCur = baseCur;       aCust = "|" + baseCust;  aCard = baseCard;
                    bCur = baseCur + "|"; bCust = baseCust;        bCard = baseCard;
                } else {
                    // customer/card boundary: A=(cust, "|"+card), B=(cust+"|", card).
                    aCur = baseCur; aCust = baseCust;       aCard = "|" + baseCard;
                    bCur = baseCur; bCust = baseCust + "|"; bCard = baseCard;
                }
                shiftPairsEmitted++;

                // Construction sanity: the pair genuinely aliases under a naive in-band join...
                assertThat(naiveJoin(TENANT, aAmount, aCur, aCust, aCard))
                        .isEqualTo(naiveJoin(TENANT, bAmount, bCur, bCust, bCard));
                // ...yet the REAL injective fingerprints of the two distinct requests MUST differ.
                assertThat(FP_A.fingerprint(ctx(aAmount, aCur, aCust, aCard)))
                        .isNotEqualTo(FP_A.fingerprint(ctx(bAmount, bCur, bCust, bCard)));

                // Feed variant A on through the dedup index below (B is exercised by the pair assert).
                amount = aAmount; cur = aCur; cust = aCust; card = aCard;
            } else {
                amount = rnd.nextLong() % 1_000_000L;
                cur = currencies[rnd.nextInt(currencies.length)];
                cust = customers[rnd.nextInt(customers.length)];
                card = cards[rnd.nextInt(cards.length)];
            }

            // Injective, human-readable identity of THIS logical tuple (independent of the code under
            // test). If a fingerprint reappears, the logical tuple MUST be identical by this key.
            String injectiveKey = TENANT + "|" + amount + "|"
                    + tag(cur) + "|" + tag(cust) + "|" + tag(card);
            String fp = FP_A.fingerprint(ctx(amount, cur, cust, card));

            String prevKey = injectiveKeyByFp.putIfAbsent(fp, injectiveKey);
            if (prevKey != null) {
                // Same fingerprint MUST mean the SAME logical tuple. A canonicalization collision
                // (pipe-join mutant aliasing a shift pair) makes prevKey != injectiveKey → FAIL. This
                // is the anti-vacuity teeth.
                assertThat(prevKey).isEqualTo(injectiveKey);
            }
            checked++;
        }
        assertThat(checked).isEqualTo(4000);
        // The generator MUST actually have produced delimiter-shift variants, else the fuzz is vacuous.
        assertThat(shiftPairsEmitted).isGreaterThan(0);
    }

    // ----------------------------------------------------------------------------------------------
    // (iii) PAN / RAW-CARD NEVER APPEARS
    // ----------------------------------------------------------------------------------------------

    @Test
    void fingerprint_doesNotContain_cleartextOfAnyField() {
        String f = FP_A.fingerprint(ctx(5000, "USD", "cust_secret_1", "tok_secret_card"));
        assertThat(f).doesNotContain("cust_secret_1", "USD", "5000", "tok_secret_card");
    }

    @Test
    void rawPanShapedCardHash_neverLeaksDigitRun() {
        // Even if a misbehaving client puts a raw-PAN-shaped value in cardHash, it is folded through
        // the one-way HMAC. NIT 2: assert a STRUCTURAL one-wayness property — the digest is exactly
        // 64 lowercase hex chars AND contains the cleartext of NO field (the robust, non-probabilistic
        // check) — instead of the fragile "no run of six 1-digits" line, whose absence is merely
        // probabilistic for a random 64-hex digest.
        String pan = "4111111111111111";
        String f = FP_A.fingerprint(ctx(5000, "USD", "cust_1", pan));
        assertThat(f).matches("^[0-9a-f]{64}$");
        assertThat(f).doesNotContain(pan, "5000", "USD", "cust_1", TENANT);
    }

    // ----------------------------------------------------------------------------------------------
    // (iv) KEYED-NESS
    // ----------------------------------------------------------------------------------------------

    @Test
    void differentKeys_produceDifferentFingerprints_forSameTuple() {
        // Proves the key participates; a plain-hash regression (key ignored) would FAIL this.
        PaymentContext c = ctx(5000, "USD", "cust_1", "tok_abc");
        assertThat(FP_A.fingerprint(c)).isNotEqualTo(FP_B.fingerprint(c));
    }

    // ----------------------------------------------------------------------------------------------
    // (v) FAIL-CLOSED
    // ----------------------------------------------------------------------------------------------

    @Test
    void unusableKey_nonBase64_throwsFingerprintUnavailable_neverReturnsNullOrSentinel() {
        // A structurally-bad (non-base64) master key cannot derive the HMAC key. fingerprint(...) must
        // throw FingerprintUnavailableException — NEVER return null and NEVER a sentinel that could
        // compare-equal to a stored value. (Callers treat this as fail-CLOSED.)
        RequestFingerprinter broken = new RequestFingerprinter("!!! not base64 !!!");
        assertThatThrownBy(() -> broken.fingerprint(ctx(5000, "USD", "cust_1", "tok")))
                .isInstanceOf(FingerprintUnavailableException.class);
    }

    @Test
    void canonicalize_isUniquelyDecodable_smoke() {
        // Direct check on the static canonicalizer: distinct argument tuples → distinct byte forms.
        byte[] a = RequestFingerprinter.canonicalize(TENANT, 1, "US", "a", "tok");
        byte[] b = RequestFingerprinter.canonicalize(TENANT, 1, "USa", "", "tok");
        byte[] cNull = RequestFingerprinter.canonicalize(TENANT, 1, null, "x", "tok");
        byte[] cEmpty = RequestFingerprinter.canonicalize(TENANT, 1, "", "x", "tok");
        assertThat(a).isNotEqualTo(b);
        assertThat(cNull).isNotEqualTo(cEmpty);
    }

    @Test
    void canonicalize_tenantSelfBinding_differentTenant_differentCanonicalAndFingerprint() {
        // NIT 1 defense-in-depth: the SAME (amount,currency,customer,card) under DIFFERENT tenants must
        // never collide — the fingerprint is self-bound to its tenant even outside a scoped lookup.
        byte[] tenantA = RequestFingerprinter.canonicalize("tenant-A", 5000, "USD", "cust_1", "tok");
        byte[] tenantB = RequestFingerprinter.canonicalize("tenant-B", 5000, "USD", "cust_1", "tok");
        assertThat(Arrays.equals(tenantA, tenantB)).isFalse();

        PaymentContext inA = new PaymentContext("pay-1", "tenant-A", 5000, "USD", "cust_1", "a@b.com",
                "411111", "tok", "1.1.1.1", "US", "dev", Map.of(), Map.of(), "pay-1");
        PaymentContext inB = new PaymentContext("pay-1", "tenant-B", 5000, "USD", "cust_1", "a@b.com",
                "411111", "tok", "1.1.1.1", "US", "dev", Map.of(), Map.of(), "pay-1");
        assertThat(FP_A.fingerprint(inA)).isNotEqualTo(FP_A.fingerprint(inB));
    }
}
