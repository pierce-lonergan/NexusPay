package io.nexuspay.analytics.application.service;

import io.nexuspay.analytics.application.dto.AnalyticsQuery;
import io.nexuspay.analytics.application.dto.PspHealthResponse;
import io.nexuspay.analytics.application.dto.PspHealthResponse.PspHealthDataPoint;
import io.nexuspay.analytics.application.port.in.QueryPspHealthUseCase;
import io.nexuspay.analytics.application.port.out.AnalyticsEventPublisher;
import io.nexuspay.analytics.application.port.out.AuthRateRollupRepository;
import io.nexuspay.analytics.application.port.out.PspHealthRepository;
import io.nexuspay.analytics.domain.event.PspHealthDegraded;
import io.nexuspay.analytics.domain.model.AnomalyAlert;
import io.nexuspay.analytics.domain.model.AuthRateMetric;
import io.nexuspay.analytics.domain.model.PspHealthScore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Computes composite PSP health scores and detects anomalies.
 *
 * <p>Health score = weighted composite of auth rate (50%), latency (30%), error rate (20%).
 * Each component is normalized to 0-100 scale.</p>
 *
 * @since 0.3.0 (Sprint 3.6)
 */
@Service
public class PspHealthScoringService implements QueryPspHealthUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(PspHealthScoringService.class);

    private static final double AUTH_RATE_WEIGHT = 0.50;
    private static final double LATENCY_WEIGHT = 0.30;
    private static final double ERROR_RATE_WEIGHT = 0.20;
    private static final int TARGET_LATENCY_MS = 2000;

    private final PspHealthRepository healthRepository;
    private final AuthRateRollupRepository authRateRepository;
    private final AnomalyDetectionService anomalyDetectionService;
    private final AnalyticsEventPublisher eventPublisher;

    public PspHealthScoringService(PspHealthRepository healthRepository,
                                    AuthRateRollupRepository authRateRepository,
                                    AnomalyDetectionService anomalyDetectionService,
                                    AnalyticsEventPublisher eventPublisher) {
        this.healthRepository = healthRepository;
        this.authRateRepository = authRateRepository;
        this.anomalyDetectionService = anomalyDetectionService;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public PspHealthResponse query(AnalyticsQuery query) {
        String pspFilter = query.filters() != null ? query.filters().get("psp") : null;

        List<PspHealthScore> scores;
        if (pspFilter != null) {
            scores = healthRepository.findLatest(query.tenantId(), pspFilter)
                    .map(List::of).orElse(List.of());
        } else {
            scores = healthRepository.findAllLatest(query.tenantId());
        }

        List<PspHealthDataPoint> data = scores.stream()
                .map(s -> new PspHealthDataPoint(
                        s.pspConnector(), s.snapshotTime(), s.healthScore(),
                        s.authRateScore(), s.latencyScore(), s.errorRateScore(),
                        s.authRate7d(), s.avgLatencyMs(), s.p95LatencyMs(),
                        s.errorRate(), s.anomalyDetected(), s.anomalyDetails()
                ))
                .toList();

        return new PspHealthResponse(data);
    }

    @Override
    public PspHealthScore calculateHealthScore(String pspConnector, String tenantId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate sevenDaysAgo = today.minusDays(7);

        List<AuthRateMetric> dailyMetrics = authRateRepository.findDaily(
                tenantId, sevenDaysAgo, today, pspConnector, null, null);

        if (dailyMetrics.isEmpty()) {
            return defaultHealthScore(tenantId, pspConnector);
        }

        // Compute 7-day averages
        double totalAttempts = 0, totalApproved = 0, totalErrors = 0;
        double latencySum = 0;
        int latencyCount = 0;
        Integer maxP95 = null;

        for (AuthRateMetric m : dailyMetrics) {
            totalAttempts += m.totalAttempts();
            totalApproved += m.totalApproved();
            totalErrors += m.totalErrors();
            if (m.avgLatencyMs() != null) {
                latencySum += m.avgLatencyMs();
                latencyCount++;
            }
            if (m.p95LatencyMs() != null) {
                maxP95 = maxP95 == null ? m.p95LatencyMs() : Math.max(maxP95, m.p95LatencyMs());
            }
        }

        double authRate7d = totalAttempts > 0 ? totalApproved / totalAttempts : 0.95;
        double errorRate = totalAttempts > 0 ? totalErrors / totalAttempts : 0;
        int avgLatency = latencyCount > 0 ? (int) (latencySum / latencyCount) : 100;

        // Normalize components to 0-100
        int authRateScore = (int) Math.min(100, authRate7d * 100);
        int latencyScore = (int) Math.max(0, 100 - ((double) avgLatency / TARGET_LATENCY_MS * 100));
        int errorRateScore = (int) Math.max(0, 100 - errorRate * 1000);

        // Weighted composite
        int healthScore = (int) (authRateScore * AUTH_RATE_WEIGHT
                + latencyScore * LATENCY_WEIGHT
                + errorRateScore * ERROR_RATE_WEIGHT);

        // Check for anomaly
        Optional<AnomalyAlert> anomaly = anomalyDetectionService.detectAuthRateAnomaly(pspConnector, tenantId);
        boolean anomalyDetected = anomaly.isPresent();

        PspHealthScore score = new PspHealthScore(
                tenantId, pspConnector, Instant.now(), healthScore,
                authRateScore, latencyScore, errorRateScore,
                BigDecimal.valueOf(authRate7d), avgLatency, maxP95,
                BigDecimal.valueOf(errorRate), anomalyDetected,
                anomaly.map(AnomalyAlert::details).orElse(Collections.emptyMap())
        );

        // Read the prior snapshot BEFORE persisting the new one — otherwise
        // findLatest returns the row we just saved and prevScore == healthScore.
        Optional<PspHealthScore> previous = healthRepository.findLatest(tenantId, pspConnector);
        int prevScore = previous.map(PspHealthScore::healthScore).orElse(100);
        boolean wasDegraded = previous.map(PspHealthScore::anomalyDetected).orElse(false);

        // Persist snapshot
        healthRepository.save(score);

        // Publish degraded event only on a healthy→degraded transition
        // (hysteresis) so consumers aren't spammed every cycle the anomaly persists.
        if (anomalyDetected && !wasDegraded) {
            eventPublisher.publish(new PspHealthDegraded(
                    tenantId, pspConnector, healthScore, prevScore,
                    "Auth rate anomaly: " + anomaly.get().alertType(),
                    Instant.now()
            ));
        }

        return score;
    }

    private PspHealthScore defaultHealthScore(String tenantId, String pspConnector) {
        return new PspHealthScore(tenantId, pspConnector, Instant.now(),
                100, 100, 100, 100,
                BigDecimal.valueOf(0.95), 100, 200,
                BigDecimal.ZERO, false, Collections.emptyMap());
    }
}
