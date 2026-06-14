package io.nexuspay.billing.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Money-math tests for {@link Price#calculateAmount(int)} — the single source of
 * truth for how much a subscription is charged. Covers all five pricing models
 * (FLAT, PER_UNIT, TIERED, VOLUME, PACKAGE), the defensive Map-key handling for
 * graduated/volume tiers, and the zero/empty edge paths. Wrong math here =
 * systematic over/under-billing of every customer, so each case asserts an exact
 * minor-unit total computed by hand.
 */
class PriceTest {

    private static Price flat(long unitAmount) {
        Price p = new Price();
        p.setPricingModel(PricingModel.FLAT);
        p.setUnitAmount(unitAmount);
        return p;
    }

    private static Price perUnit(long unitAmount) {
        Price p = new Price();
        p.setPricingModel(PricingModel.PER_UNIT);
        p.setUnitAmount(unitAmount);
        return p;
    }

    private static Price tiered(List<Map<String, Object>> tiers) {
        Price p = new Price();
        p.setPricingModel(PricingModel.TIERED);
        p.setTiers(tiers);
        return p;
    }

    private static Price volume(List<Map<String, Object>> tiers) {
        Price p = new Price();
        p.setPricingModel(PricingModel.VOLUME);
        p.setTiers(tiers);
        return p;
    }

    private static Price pkg(Long unitAmount, List<Map<String, Object>> tiers) {
        Price p = new Price();
        p.setPricingModel(PricingModel.PACKAGE);
        p.setUnitAmount(unitAmount);
        p.setTiers(tiers);
        return p;
    }

    // ---- FLAT ----

    @Test
    void flatIgnoresQuantity() {
        Price p = flat(4900);
        assertThat(p.calculateAmount(1)).isEqualTo(4900);
        assertThat(p.calculateAmount(7)).isEqualTo(4900);
        assertThat(p.calculateAmount(0)).isEqualTo(4900);
    }

    // ---- PER_UNIT ----

    @Test
    void perUnitMultipliesByQuantity() {
        Price p = perUnit(1000);
        assertThat(p.calculateAmount(3)).isEqualTo(3000);
    }

    @Test
    void perUnitZeroQuantityIsZero() {
        assertThat(perUnit(1000).calculateAmount(0)).isEqualTo(0);
    }

    // ---- TIERED (graduated) ----

    @Test
    void tieredAccumulatesAcrossBands() {
        // Band 1: up to 10 @ 100; Band 2: up to 20 @ 50.
        // qty 15 = 10*100 (full band 1) + 5*50 (partial band 2) = 1250.
        Price p = tiered(List.of(
                Map.of("up_to", 10, "unit_amount", 100),
                Map.of("up_to", 20, "unit_amount", 50)
        ));
        assertThat(p.calculateAmount(15)).isEqualTo(1250);
    }

    @Test
    void tieredBoundaryQuantityConsumesOnlyFirstBand() {
        // qty exactly == first band's up_to (10) must NOT spill into band 2.
        Price p = tiered(List.of(
                Map.of("up_to", 10, "unit_amount", 100),
                Map.of("up_to", 20, "unit_amount", 50)
        ));
        assertThat(p.calculateAmount(10)).isEqualTo(1000);
    }

    @Test
    void tieredLastBandHasNoUpToAndCatchesOverflow() {
        // Last band omits up_to (defaults to Integer.MAX_VALUE).
        // qty 25 = 10*100 (band1) + 15*50 (band2, unbounded) = 1750.
        Price p = tiered(List.of(
                Map.of("up_to", 10, "unit_amount", 100),
                Map.of("unit_amount", 50)
        ));
        assertThat(p.calculateAmount(25)).isEqualTo(1750);
    }

    @Test
    void tieredFlatAmountPerBandAddedOnce() {
        // Each consumed band adds its flat_amount exactly once.
        // qty 15 = (10*100 + 500) + (5*50 + 200) = 1500 + 450 = 1950.
        Price p = tiered(List.of(
                Map.of("up_to", 10, "unit_amount", 100, "flat_amount", 500),
                Map.of("up_to", 20, "unit_amount", 50, "flat_amount", 200)
        ));
        assertThat(p.calculateAmount(15)).isEqualTo(1950);
    }

    @Test
    void tieredFlatAmountOfUnconsumedBandNotAdded() {
        // qty 5 only consumes band 1; band 2's flat_amount must not be charged.
        Price p = tiered(List.of(
                Map.of("up_to", 10, "unit_amount", 100, "flat_amount", 500),
                Map.of("up_to", 20, "unit_amount", 50, "flat_amount", 200)
        ));
        assertThat(p.calculateAmount(5)).isEqualTo(5 * 100 + 500);
    }

    @Test
    void tieredMissingUnitAndFlatDefaultToZero() {
        // A band with neither unit_amount nor flat_amount contributes 0.
        Price p = tiered(List.of(
                Map.of("up_to", 10),
                Map.of("up_to", 20, "unit_amount", 50)
        ));
        // qty 15: band1 contributes 0, band2 contributes 5*50 = 250.
        assertThat(p.calculateAmount(15)).isEqualTo(250);
    }

    @Test
    void tieredNullTiersReturnsZero() {
        assertThat(tiered(null).calculateAmount(10)).isEqualTo(0);
    }

    @Test
    void tieredEmptyTiersReturnsZero() {
        assertThat(tiered(List.of()).calculateAmount(10)).isEqualTo(0);
    }

    @Test
    void tieredZeroQuantityReturnsZero() {
        Price p = tiered(List.of(Map.of("up_to", 10, "unit_amount", 100, "flat_amount", 500)));
        assertThat(p.calculateAmount(0)).isEqualTo(0);
    }

    // ---- VOLUME ----

    @Test
    void volumeUsesSingleMatchingTierForWholeQuantity() {
        // qty 5 <= 10 -> all 5 units at that tier's unit_amount + its flat_amount.
        Price p = volume(List.of(
                Map.of("up_to", 10, "unit_amount", 100, "flat_amount", 200),
                Map.of("up_to", 20, "unit_amount", 50)
        ));
        assertThat(p.calculateAmount(5)).isEqualTo(5 * 100 + 200);
    }

    @Test
    void volumeSelectsHigherTierWhenQuantityExceedsFirst() {
        // qty 15 falls in the up_to 20 band -> all 15 units at 50.
        Price p = volume(List.of(
                Map.of("up_to", 10, "unit_amount", 100),
                Map.of("up_to", 20, "unit_amount", 50)
        ));
        assertThat(p.calculateAmount(15)).isEqualTo(15 * 50);
    }

    @Test
    void volumeAboveAllTiersReturnsZero() {
        // qty 25 exceeds every bounded up_to and no catch-all tier -> 0.
        Price p = volume(List.of(
                Map.of("up_to", 10, "unit_amount", 100),
                Map.of("up_to", 20, "unit_amount", 50)
        ));
        assertThat(p.calculateAmount(25)).isEqualTo(0);
    }

    @Test
    void volumeNullTiersReturnsZero() {
        assertThat(volume(null).calculateAmount(10)).isEqualTo(0);
    }

    @Test
    void volumeEmptyTiersReturnsZero() {
        assertThat(volume(List.of()).calculateAmount(10)).isEqualTo(0);
    }

    // ---- PACKAGE ----

    @Test
    void packageRoundsUpPartialPackage() {
        // package size 10 @ 1000/package. qty 11 -> ceil(11/10) = 2 packages = 2000.
        Price p = pkg(1000L, List.of(Map.of("up_to", 10)));
        assertThat(p.calculateAmount(11)).isEqualTo(2000);
    }

    @Test
    void packageExactMultipleIsOnePackage() {
        Price p = pkg(1000L, List.of(Map.of("up_to", 10)));
        assertThat(p.calculateAmount(10)).isEqualTo(1000);
    }

    @Test
    void packageSingleUnitIsOnePackage() {
        Price p = pkg(1000L, List.of(Map.of("up_to", 10)));
        assertThat(p.calculateAmount(1)).isEqualTo(1000);
    }

    @Test
    void packageNullUnitAmountReturnsZero() {
        Price p = pkg(null, List.of(Map.of("up_to", 10)));
        assertThat(p.calculateAmount(11)).isEqualTo(0);
    }

    @Test
    void packageZeroUnitAmountReturnsZero() {
        Price p = pkg(0L, List.of(Map.of("up_to", 10)));
        assertThat(p.calculateAmount(11)).isEqualTo(0);
    }

    @Test
    void packageDefaultsSizeToOneWhenTiersEmpty() {
        // No tiers -> packageSize 1 -> each unit is its own package.
        Price p = pkg(1000L, List.of());
        assertThat(p.calculateAmount(3)).isEqualTo(3000);
    }

    @Test
    void packageDefaultsSizeToOneWhenTiersNull() {
        Price p = pkg(1000L, null);
        assertThat(p.calculateAmount(3)).isEqualTo(3000);
    }
}
