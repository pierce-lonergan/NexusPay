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

        double[] rates = dailyMetrics.stream()
                .mapToDouble(m -> m.authRate().doubleValue())
                .toArray();

        // The most recent data point is the value under test. It must be
        // EXCLUDED from the baseline: including it both drags the mean down and
        // inflates the deviation, to the point that a single-day crash can
        // never mathematically exceed 2 sigma for small windows.
        double currentRate = rates[rates.length - 1];
        int n = rates.length - 1;

        double mean = 0;
        for (int i = 0; i < n; i++) mean += rates[i];
        mean /= n;

        // Sample variance (divide by n-1) — these are a small sample of daily
        // observations, not the full population.
        double variance = 0;
        for (int i = 0; i < n; i++) variance += (rates[i] - mean) * (rates[i] - mean);
        variance /= (n - 1);

        // Floor the deviation so a flat baseline (zero variance) still allows a
        // large absolute drop to alert, while tiny wobbles stay below the bound.
        double stdDev = Math.max(Math.sqrt(variance), 0.001);
        double lowerBound = mean - (stdDevThreshold * stdDev);

        if (currentRate < lowerBound) {
            double deviation = (mean - currentRate) / stdDev;
            LOG.warn("Anomaly detected for PSP {}: auth rate {} is {} sigma below 7-day mean {}",
                    pspConnector, String.format("%.4f", currentRate),
                    String.format("%.1f", deviation), String.format("%.4f", mean));

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
