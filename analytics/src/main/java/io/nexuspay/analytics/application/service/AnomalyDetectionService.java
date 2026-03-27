package io.nexuspay.analytics.application.service;

import io.nexuspay.analytics.application.port.out.AuthRateRollupRepository;
import io.nexuspay.analytics.domain.model.AnomalyAlert;
import io.nexuspay.analytics.domain.model.AuthRateMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Detects anomalies in analytics metrics using statistical methods.
 * Currently supports auth rate anomaly detection via standard deviation threshold.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
@Service
public class AnomalyDetectionService {

    private static final Logger LOG = LoggerFactory.getLogger(AnomalyDetectionService.class);
    private static final double DEFAULT_STD_DEV_THRESHOLD = 2.0;

    private final AuthRateRollupRepository authRateRepository;

    public AnomalyDetectionService(AuthRateRollupRepository authRateRepository) {
        this.authRateRepository = authRateRepository;
    }

    /**
     * Detects if the current auth rate for a PSP is anomalous by comparing
     * against the 7-day rolling average ± N standard deviations.
     */
    public Optional<AnomalyAlert> detectAuthRateAnomaly(String pspConnector, String tenantId,
                                                         double stdDevThreshold) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate sevenDaysAgo = today.minusDays(7);

        List<AuthRateMetric> dailyMetrics = authRateRepository.findDaily(
                tenantId, sevenDaysAgo, today, pspConnector, null, null);

        if (dailyMetrics.size() < 3) {
            LOG.debug("Not enough data points ({}) for anomaly detection on PSP {}",
                    dailyMetrics.size(), pspConnector);
            return Optional.empty();
        }

        // Compute mean and standard deviation of auth rates
        double[] rates = dailyMetrics.stream()
                .mapToDouble(m -> m.authRate().doubleValue())
                .toArray();

        double mean = 0;
        for (double r : rates) mean += r;
        mean /= rates.length;

        double variance = 0;
        for (double r : rates) variance += (r - mean) * (r - mean);
        variance /= rates.length;
        double stdDev = Math.sqrt(variance);

        // Get the most recent data point as "current"
        double currentRate = rates[rates.length - 1];
        double lowerBound = mean - (stdDevThreshold * stdDev);

        if (currentRate < lowerBound && stdDev > 0.001) {
            double deviation = (mean - currentRate) / stdDev;
            LOG.warn("Anomaly detected for PSP {}: auth rate {:.4f} is {:.1f}σ below 7-day mean {:.4f}",
                    pspConnector, currentRate, deviation, mean);

            return Optional.of(new AnomalyAlert(
                    tenantId,
                    pspConnector,
                    "AUTH_RATE_DROP",
                    Map.of(
                            "currentRate", currentRate,
                            "mean7d", mean,
                            "stdDev", stdDev,
                            "deviation", deviation,
                            "threshold", stdDevThreshold,
                            "lowerBound", lowerBound,
                            "dataPoints", rates.length
                    ),
                    Instant.now()
            ));
        }

        return Optional.empty();
    }

    /**
     * Convenience method using default threshold (2σ).
     */
    public Optional<AnomalyAlert> detectAuthRateAnomaly(String pspConnector, String tenantId) {
        return detectAuthRateAnomaly(pspConnector, tenantId, DEFAULT_STD_DEV_THRESHOLD);
    }
}
