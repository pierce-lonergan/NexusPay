package io.nexuspay.analytics.application.service;

import io.nexuspay.analytics.application.dto.AnalyticsQuery;
import io.nexuspay.analytics.application.dto.PspHealthResponse;
import io.nexuspay.analytics.application.port.out.AnalyticsEventPublisher;
import io.nexuspay.analytics.application.port.out.AuthRateRollupRepository;
import io.nexuspay.analytics.application.port.out.PspHealthRepository;
import io.nexuspay.analytics.domain.event.PspHealthDegraded;
import io.nexuspay.analytics.domain.model.AnomalyAlert;
import io.nexuspay.analytics.domain.model.AuthRateMetric;
import io.nexuspay.analytics.domain.model.PspHealthScore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PspHealthScoringServiceTest {

    @Mock
    private PspHealthRepository healthRepository;

    @Mock
    private AuthRateRollupRepository authRateRepository;

    @Mock
    private AnomalyDetectionService anomalyDetectionService;

    @Mock
    private AnalyticsEventPublisher eventPublisher;

    @InjectMocks
    private PspHealthScoringService service;

    private static final String TENANT = "tenant-1";
    private static final String PSP = "stripe";

    private AuthRateMetric metric(double authRate, int avgLatencyMs, int errors, int attempts) {
        return new AuthRateMetric(
                TENANT, Instant.now(), PSP, "visa", "credit", "US", "USD", "card",
                attempts, (int) (attempts * authRate), attempts - (int) (attempts * authRate) - errors, errors,
                BigDecimal.valueOf(authRate), avgLatencyMs, null, null, null
        );
    }

    @Test
    void calculateHealthScore_withNormalData_returnsScoreBetween0And100() {
        List<AuthRateMetric> metrics = List.of(
                metric(0.95, 150, 5, 1000),
                metric(0.94, 160, 6, 1000),
                metric(0.96, 140, 4, 1000)
        );
        when(authRateRepository.findDaily(eq(TENANT), any(LocalDate.class), any(LocalDate.class), eq(PSP), any(), any()))
                .thenReturn(metrics);
        when(anomalyDetectionService.detectAuthRateAnomaly(PSP, TENANT)).thenReturn(Optional.empty());

        PspHealthScore score = service.calculateHealthScore(PSP, TENANT);

        assertThat(score.healthScore()).isBetween(0, 100);
        assertThat(score.pspConnector()).isEqualTo(PSP);
        assertThat(score.tenantId()).isEqualTo(TENANT);
        verify(healthRepository).save(any(PspHealthScore.class));
    }

    @Test
    void calculateHealthScore_authRateNormalization_095AuthRateYields95AuthScore() {
        List<AuthRateMetric> metrics = List.of(metric(0.95, 100, 0, 1000));
        when(authRateRepository.findDaily(eq(TENANT), any(LocalDate.class), any(LocalDate.class), eq(PSP), any(), any()))
                .thenReturn(metrics);
        when(anomalyDetectionService.detectAuthRateAnomaly(PSP, TENANT)).thenReturn(Optional.empty());

        PspHealthScore score = service.calculateHealthScore(PSP, TENANT);

        assertThat(score.authRateScore()).isEqualTo(95);
    }

    @Test
    void calculateHealthScore_latencyNormalization_100msYieldsHighScore() {
        List<AuthRateMetric> metrics = List.of(metric(0.95, 100, 0, 1000));
        when(authRateRepository.findDaily(eq(TENANT), any(LocalDate.class), any(LocalDate.class), eq(PSP), any(), any()))
                .thenReturn(metrics);
        when(anomalyDetectionService.detectAuthRateAnomaly(PSP, TENANT)).thenReturn(Optional.empty());

        PspHealthScore score = service.calculateHealthScore(PSP, TENANT);

        // 100ms / 2000ms * 100 = 5 => 100 - 5 = 95
        assertThat(score.latencyScore()).isEqualTo(95);
    }

    @Test
    void calculateHealthScore_latencyNormalization_2000msYieldsZeroScore() {
        List<AuthRateMetric> metrics = List.of(metric(0.95, 2000, 0, 1000));
        when(authRateRepository.findDaily(eq(TENANT), any(LocalDate.class), any(LocalDate.class), eq(PSP), any(), any()))
                .thenReturn(metrics);
        when(anomalyDetectionService.detectAuthRateAnomaly(PSP, TENANT)).thenReturn(Optional.empty());

        PspHealthScore score = service.calculateHealthScore(PSP, TENANT);

        // 2000ms / 2000ms * 100 = 100 => 100 - 100 = 0
        assertThat(score.latencyScore()).isEqualTo(0);
    }

    @Test
    void calculateHealthScore_errorRateNormalization_zeroErrorsYields100() {
        List<AuthRateMetric> metrics = List.of(metric(0.95, 100, 0, 1000));
        when(authRateRepository.findDaily(eq(TENANT), any(LocalDate.class), any(LocalDate.class), eq(PSP), any(), any()))
                .thenReturn(metrics);
        when(anomalyDetectionService.detectAuthRateAnomaly(PSP, TENANT)).thenReturn(Optional.empty());

        PspHealthScore score = service.calculateHealthScore(PSP, TENANT);

        assertThat(score.errorRateScore()).isEqualTo(100);
    }

    @Test
    void calculateHealthScore_errorRateNormalization_01ErrorRateYields0() {
        // 0.1 error rate => 100 - 0.1*1000 = 100 - 100 = 0
        List<AuthRateMetric> metrics = List.of(metric(0.80, 100, 100, 1000));
        when(authRateRepository.findDaily(eq(TENANT), any(LocalDate.class), any(LocalDate.class), eq(PSP), any(), any()))
                .thenReturn(metrics);
        when(anomalyDetectionService.detectAuthRateAnomaly(PSP, TENANT)).thenReturn(Optional.empty());

        PspHealthScore score = service.calculateHealthScore(PSP, TENANT);

        assertThat(score.errorRateScore()).isEqualTo(0);
    }

    @Test
    void calculateHealthScore_emptyDailyMetrics_returnsDefaultScore100() {
        when(authRateRepository.findDaily(eq(TENANT), any(LocalDate.class), any(LocalDate.class), eq(PSP), any(), any()))
                .thenReturn(List.of());

        PspHealthScore score = service.calculateHealthScore(PSP, TENANT);

        assertThat(score.healthScore()).isEqualTo(100);
        assertThat(score.anomalyDetected()).isFalse();
    }

    @Test
    void calculateHealthScore_weightedComposite_correctWeighting() {
        // auth rate = 0.90 => authRateScore = 90 (weight 0.50) => 45
        // latency = 500ms => latencyScore = 100 - (500/2000*100) = 75 (weight 0.30) => 22.5
        // error = 0 => errorRateScore = 100 (weight 0.20) => 20
        // total = 45 + 22.5 + 20 = 87.5 => 87 (int cast)
        List<AuthRateMetric> metrics = List.of(
                new AuthRateMetric(TENANT, Instant.now(), PSP, "visa", "credit", "US", "USD", "card",
                        1000, 900, 100, 0, BigDecimal.valueOf(0.90), 500, null, null, null)
        );
        when(authRateRepository.findDaily(eq(TENANT), any(LocalDate.class), any(LocalDate.class), eq(PSP), any(), any()))
                .thenReturn(metrics);
        when(anomalyDetectionService.detectAuthRateAnomaly(PSP, TENANT)).thenReturn(Optional.empty());

        PspHealthScore score = service.calculateHealthScore(PSP, TENANT);

        assertThat(score.healthScore()).isEqualTo(87);
    }

    @Test
    void calculateHealthScore_anomalyDetected_publishesPspHealthDegradedEvent() {
        List<AuthRateMetric> metrics = List.of(metric(0.70, 500, 50, 1000));
        when(authRateRepository.findDaily(eq(TENANT), any(LocalDate.class), any(LocalDate.class), eq(PSP), any(), any()))
                .thenReturn(metrics);

        AnomalyAlert alert = new AnomalyAlert(TENANT, PSP, "AUTH_RATE_DROP",
                Map.of("currentRate", 0.70, "mean7d", 0.95), Instant.now());
        when(anomalyDetectionService.detectAuthRateAnomaly(PSP, TENANT)).thenReturn(Optional.of(alert));
        when(healthRepository.findLatest(TENANT, PSP)).thenReturn(Optional.empty());

        PspHealthScore score = service.calculateHealthScore(PSP, TENANT);

        assertThat(score.anomalyDetected()).isTrue();
        verify(eventPublisher).publish(any(PspHealthDegraded.class));
    }

    @Test
    void query_delegatesToRepositoryFindAllLatest() {
        PspHealthScore healthScore = new PspHealthScore(TENANT, PSP, Instant.now(), 90,
                95, 85, 100, BigDecimal.valueOf(0.95), 150, 300,
                BigDecimal.ZERO, false, Collections.emptyMap());
        when(healthRepository.findAllLatest(TENANT)).thenReturn(List.of(healthScore));

        AnalyticsQuery query = new AnalyticsQuery(null, null, null, List.of(), Map.of(), TENANT);
        PspHealthResponse response = service.query(query);

        verify(healthRepository).findAllLatest(TENANT);
        assertThat(response.data()).hasSize(1);
        assertThat(response.data().get(0).pspConnector()).isEqualTo(PSP);
    }
}
