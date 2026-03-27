package io.nexuspay.analytics.application.service;

import io.nexuspay.analytics.application.dto.AnalyticsQuery;
import io.nexuspay.analytics.application.dto.AuthRateResponse;
import io.nexuspay.analytics.application.port.out.AuthRateRollupRepository;
import io.nexuspay.analytics.domain.model.AnalyticsDimension;
import io.nexuspay.analytics.domain.model.AuthRateMetric;
import io.nexuspay.analytics.domain.model.TimeGranularity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthRateAnalyticsServiceTest {

    @Mock
    private AuthRateRollupRepository repository;

    @InjectMocks
    private AuthRateAnalyticsService service;

    private static final String TENANT = "tenant-1";
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-01-15T00:00:00Z");

    private AuthRateMetric sampleMetric(String psp) {
        return new AuthRateMetric(
                TENANT, FROM, psp, "visa", "credit", "US", "USD", "card",
                1000, 950, 40, 10, BigDecimal.valueOf(0.95),
                120, 100, 200, 350
        );
    }

    @Test
    void query_withHourlyGranularity_callsFindHourly() {
        AnalyticsQuery query = new AnalyticsQuery(FROM, TO, TimeGranularity.HOURLY, List.of(), Map.of(), TENANT);
        when(repository.findHourly(eq(TENANT), eq(FROM), eq(TO), any(), any(), any()))
                .thenReturn(List.of(sampleMetric("stripe")));

        AuthRateResponse response = service.query(query);

        verify(repository).findHourly(eq(TENANT), eq(FROM), eq(TO), any(), any(), any());
        assertThat(response.data()).hasSize(1);
        assertThat(response.granularity()).isEqualTo(TimeGranularity.HOURLY);
    }

    @Test
    void query_withDailyGranularity_callsFindDaily() {
        AnalyticsQuery query = new AnalyticsQuery(FROM, TO, TimeGranularity.DAILY, List.of(), Map.of(), TENANT);
        LocalDate fromDate = LocalDate.ofInstant(FROM, ZoneOffset.UTC);
        LocalDate toDate = LocalDate.ofInstant(TO, ZoneOffset.UTC);
        when(repository.findDaily(eq(TENANT), eq(fromDate), eq(toDate), any(), any(), any()))
                .thenReturn(List.of(sampleMetric("adyen")));

        AuthRateResponse response = service.query(query);

        verify(repository).findDaily(eq(TENANT), eq(fromDate), eq(toDate), any(), any(), any());
        assertThat(response.data()).hasSize(1);
    }

    @Test
    void query_withMonthlyGranularity_callsFindMonthly() {
        AnalyticsQuery query = new AnalyticsQuery(FROM, TO, TimeGranularity.MONTHLY, List.of(), Map.of(), TENANT);
        LocalDate fromDate = LocalDate.ofInstant(FROM, ZoneOffset.UTC);
        LocalDate toDate = LocalDate.ofInstant(TO, ZoneOffset.UTC);
        when(repository.findMonthly(eq(TENANT), eq(fromDate), eq(toDate), any(), any(), any()))
                .thenReturn(List.of(sampleMetric("checkout")));

        AuthRateResponse response = service.query(query);

        verify(repository).findMonthly(eq(TENANT), eq(fromDate), eq(toDate), any(), any(), any());
        assertThat(response.data()).hasSize(1);
    }

    @Test
    void query_withNullGranularity_defaultsToDaily() {
        AnalyticsQuery query = new AnalyticsQuery(FROM, TO, null, List.of(), Map.of(), TENANT);
        LocalDate fromDate = LocalDate.ofInstant(FROM, ZoneOffset.UTC);
        LocalDate toDate = LocalDate.ofInstant(TO, ZoneOffset.UTC);
        when(repository.findDaily(eq(TENANT), eq(fromDate), eq(toDate), any(), any(), any()))
                .thenReturn(List.of());

        AuthRateResponse response = service.query(query);

        verify(repository).findDaily(eq(TENANT), eq(fromDate), eq(toDate), any(), any(), any());
        assertThat(response.granularity()).isEqualTo(TimeGranularity.DAILY);
    }

    @Test
    void query_groupByPsp_buildsDimensionMap() {
        AuthRateMetric metric = sampleMetric("stripe");
        AnalyticsQuery query = new AnalyticsQuery(FROM, TO, TimeGranularity.HOURLY,
                List.of(AnalyticsDimension.PSP), Map.of(), TENANT);
        when(repository.findHourly(eq(TENANT), eq(FROM), eq(TO), any(), any(), any()))
                .thenReturn(List.of(metric));

        AuthRateResponse response = service.query(query);

        assertThat(response.data()).hasSize(1);
        assertThat(response.data().get(0).dimensions()).containsEntry("psp", "stripe");
    }

    @Test
    void query_dateRangeExceeds365Days_throwsIllegalArgumentException() {
        Instant farFuture = FROM.plusSeconds(366L * 24 * 3600);
        AnalyticsQuery query = new AnalyticsQuery(FROM, farFuture, TimeGranularity.DAILY, List.of(), Map.of(), TENANT);

        assertThatThrownBy(() -> service.query(query))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("365");
    }

    @Test
    void query_nullFromOrTo_throwsIllegalArgumentException() {
        AnalyticsQuery queryNullFrom = new AnalyticsQuery(null, TO, TimeGranularity.DAILY, List.of(), Map.of(), TENANT);
        AnalyticsQuery queryNullTo = new AnalyticsQuery(FROM, null, TimeGranularity.DAILY, List.of(), Map.of(), TENANT);

        assertThatThrownBy(() -> service.query(queryNullFrom))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");

        assertThatThrownBy(() -> service.query(queryNullTo))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }

    @Test
    void query_fromAfterTo_throwsIllegalArgumentException() {
        AnalyticsQuery query = new AnalyticsQuery(TO, FROM, TimeGranularity.DAILY, List.of(), Map.of(), TENANT);

        assertThatThrownBy(() -> service.query(query))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before");
    }
}
