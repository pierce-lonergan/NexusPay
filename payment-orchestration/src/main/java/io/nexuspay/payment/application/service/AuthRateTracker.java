package io.nexuspay.payment.application.service;

import io.nexuspay.payment.application.port.routing.AuthRateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Tracks and queries historical auth rates segmented by card attributes.
 * Feeds into the SuccessRateStrategy for data-driven routing.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
@Service
public class AuthRateTracker {

    private static final Logger LOG = LoggerFactory.getLogger(AuthRateTracker.class);

    private final AuthRateRepository authRateRepository;
    private final int minSampleSize;

    public AuthRateTracker(
            AuthRateRepository authRateRepository,
            @Value("${nexuspay.routing.health.min-sample-size:100}") int minSampleSize) {
        this.authRateRepository = authRateRepository;
        this.minSampleSize = minSampleSize;
    }

    /**
     * Records a transaction result for auth rate calculations.
     */
    public void recordResult(String pspConnector, String cardBrand, String cardType,
                              String issuingCountry, boolean authorized) {
        authRateRepository.recordResult(pspConnector, cardBrand, cardType, issuingCountry, authorized);
    }

    /**
     * Gets the auth rate for a PSP, with optional card attribute filtering.
     * Falls back to overall rate if segmented data has insufficient samples.
     */
    public double getAuthRate(String pspConnector, String cardBrand, String cardType, String issuingCountry) {
        // Try segmented rate first
        if (cardBrand != null || cardType != null) {
            Optional<Double> segmented = authRateRepository.getAuthRate(
                    pspConnector, cardBrand, cardType, issuingCountry);
            if (segmented.isPresent()) {
                long sampleSize = authRateRepository.getSampleSize(pspConnector);
                if (sampleSize >= minSampleSize) {
                    return segmented.get();
                }
            }
        }

        // Fall back to overall auth rate
        return authRateRepository.getOverallAuthRate(pspConnector).orElse(0.95);
    }
}
