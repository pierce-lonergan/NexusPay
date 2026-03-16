package io.nexuspay.payment.application.port.routing;

import java.util.Optional;

/**
 * Outbound port for querying historical auth rates, segmented by
 * card brand, card type, and issuing region.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
public interface AuthRateRepository {

    /**
     * Gets the auth rate for a PSP, optionally filtered by card attributes.
     * Returns the rate as a fraction (0.0 to 1.0).
     */
    Optional<Double> getAuthRate(String pspConnector, String cardBrand, String cardType, String issuingCountry);

    /**
     * Gets the overall auth rate for a PSP (unfiltered).
     */
    Optional<Double> getOverallAuthRate(String pspConnector);

    /**
     * Records a transaction result for auth rate tracking.
     */
    void recordResult(String pspConnector, String cardBrand, String cardType,
                      String issuingCountry, boolean authorized);

    /**
     * Gets the sample size for a PSP's auth rate calculation.
     */
    long getSampleSize(String pspConnector);
}
