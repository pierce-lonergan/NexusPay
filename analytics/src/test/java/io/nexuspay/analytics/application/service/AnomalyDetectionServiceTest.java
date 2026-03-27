package io.nexuspay.analytics.application.service;

import io.nexuspay.analytics.application.port.out.AuthRateRollupRepository;
import io.nexuspay.analytics.domain.model.AnomalyAlert;
import io.nexuspay.analytics.domain.model.AuthRateMetric;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnomalyDetectionServiceTest {

    @Mock
    private AuthRateRollupRepository authRateRepository;

    @InjectMocks
    private AnomalyDetectionService service;

    private static final String TENANT = "tenant-1";
    private static final String PSP = "stripe";

    private AuthRateMetric metricWithRate(double authRate) {
        return new AuthRateMetric(
                TENANT, Instant.now(), PSP, "visa", "credit", "US", "USD", "card",
                1000, (int) (1000 * authRate), 1000 - (int) (1000 * authRate), 0,
                BigDecimal.valueOf(authRate), 150, null, null, null
        );
    }

    @Test
    void detectAuthRateAnomaly_stableRates_returnsEmpty() {
        List<AuthRateMetric> metrics = List.of(
                metricWithRate(0.95),
                metricWithRate(0.94),
                metricWithRate(0.96),
                metricWithRate(0.95),
                metricWithRate(0.95)
        );
        when(authRateRepository.findDaily(eq(TENANT), any(LocalDate.class), any(LocalDate.class), eq(PSP), any(), any()))
                .thenReturn(metrics);

        Optional<AnomalyAlert> result = service.detectAuthRateAnomaly(PSP, TENANT);

        assertThat(result).isEmpty();
    }

    @Test
    void detectAuthRateAnomaly_rateDropsMoreThan2Sigma_returnsAnomaly() {
        // First 4 points stable around 0.95, last point drops to 0.50
        List<AuthRateMetric> metrics = List.of(
                metricWithRate(0.95),
                metricWithRate(0.95),
                metricWithRate(0.95),
                metricWithRate(0.95),
                metricWithRate(0.50)
        );
        when(authRateRepository.findDaily(eq(TENANT), any(LocalDate.class), any(LocalDate.class), eq(PSP), any(), any()))
                .thenReturn(metrics);

        Optional<AnomalyAlert> result = service.detectAuthRateAnomaly(PSP, TENANT);

        assertThat(result).isPresent();
        assertThat(result.get().alertType()).isEqualTo("AUTH_RATE_DROP");
        assertThat(result.get().pspConnector()).isEqualTo(PSP);
    }

    @Test
    void detectAuthRateAnomaly_lessThan3DataPoints_returnsEmpty() {
        List<AuthRateMetric> metrics = List.of(
                metricWithRate(0.95),
                metricWithRate(0.50)
        );
        when(authRateRepository.findDaily(eq(TENANT), any(LocalDate.class), any(LocalDate.class), eq(PSP), any(), any()))
                .thenReturn(metrics);

        Optional<AnomalyAlert> result = service.detectAuthRateAnomaly(PSP, TENANT);

        assertThat(result).isEmpty();
    }

    @Test
    void detectAuthRateAnomaly_verySmallStdDev_doesNotFalselyTrigger() {
        // All rates identical => stdDev near zero, last point barely below mean
        List<AuthRateMetric> metrics = List.of(
                metricWithRate(0.950),
                metricWithRate(0.950),
                metricWithRate(0.950),
                metricWithRate(0.949)
        );
        when(authRateRepository.findDaily(eq(TENANT), any(LocalDate.class), any(LocalDate.class), eq(PSP), any(), any()))
                .thenReturn(metrics);

        Optional<AnomalyAlert> result = service.detectAuthRateAnomaly(PSP, TENANT);

        // stdDev < 0.001 guard should prevent false positive
        assertThat(result).isEmpty();
    }

    @Test
    void detectAuthRateAnomaly_customThreshold1Sigma_isMoreSensitive() {
        // Moderate drop that would not trigger at 2sigma but triggers at 1sigma
        List<AuthRateMetric> metrics = List.of(
                metricWithRate(0.95),
                metricWithRate(0.94),
                metricWithRate(0.96),
                metricWithRate(0.93),
                metricWithRate(0.85)
        );
        when(authRateRepository.findDaily(eq(TENANT), any(LocalDate.class), any(LocalDate.class), eq(PSP), any(), any()))
                .thenReturn(metrics);

        // Default 2-sigma might not trigger, but 1-sigma should
        Optional<AnomalyAlert> resultLowThreshold = service.detectAuthRateAnomaly(PSP, TENANT, 1.0);

        assertThat(resultLowThreshold).isPresent();
    }

    @Test
    void detectAuthRateAnomaly_returnsCorrectAlertDetails() {
        List<AuthRateMetric> metrics = List.of(
                metricWithRate(0.95),
                metricWithRate(0.95),
                metricWithRate(0.95),
                metricWithRate(0.95),
                metricWithRate(0.50)
        );
        when(authRateRepository.findDaily(eq(TENANT), any(LocalDate.class), any(LocalDate.class), eq(PSP), any(), any()))
                .thenReturn(metrics);

        Optional<AnomalyAlert> result = service.detectAuthRateAnomaly(PSP, TENANT);

        assertThat(result).isPresent();
        AnomalyAlert alert = result.get();
        assertThat(alert.tenantId()).isEqualTo(TENANT);
        assertThat(alert.pspConnector()).isEqualTo(PSP);
        assertThat(alert.alertType()).isEqualTo("AUTH_RATE_DROP");
        assertThat(alert.details()).containsKeys("currentRate", "mean7d", "stdDev", "deviation", "threshold", "lowerBound", "dataPoints");
        assertThat((double) alert.details().get("currentRate")).isEqualTo(0.50, org.assertj.core.api.Assertions.within(0.01));
        assertThat((int) alert.details().get("dataPoints")).isEqualTo(5);
        assertThat(alert.detectedAt()).isNotNull();
    }
}
