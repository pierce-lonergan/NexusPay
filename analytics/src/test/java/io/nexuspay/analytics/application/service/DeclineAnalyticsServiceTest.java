package io.nexuspay.analytics.application.service;

import io.nexuspay.analytics.application.dto.AnalyticsQuery;
import io.nexuspay.analytics.application.dto.DeclineResponse;
import io.nexuspay.analytics.application.port.out.DeclineRollupRepository;
import io.nexuspay.analytics.domain.model.AnalyticsDimension;
import io.nexuspay.analytics.domain.model.DeclineAnalysis;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeclineAnalyticsServiceTest {

    @Mock
    private DeclineRollupRepository repository;

    @InjectMocks
    private DeclineAnalyticsService service;

    private static final String TENANT = "tenant-1";
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-01-15T00:00:00Z");

    @Test
    void categorizeDecline_insufficientFunds_returnsSoft() {
        assertThat(DeclineAnalyticsService.categorizeDecline("insufficient_funds")).isEqualTo("SOFT");
    }

    @Test
    void categorizeDecline_expiredCard_returnsHard() {
        assertThat(DeclineAnalyticsService.categorizeDecline("expired_card")).isEqualTo("HARD");
    }

    @Test
    void categorizeDecline_unknownCode_returnsError() {
        assertThat(DeclineAnalyticsService.categorizeDecline("unknown_code")).isEqualTo("ERROR");
    }

    @Test
    void categorizeDecline_null_returnsError() {
        assertThat(DeclineAnalyticsService.categorizeDecline(null)).isEqualTo("ERROR");
    }

    @Test
    void query_callsFindDailyWithCorrectDateRange() {
        LocalDate fromDate = LocalDate.ofInstant(FROM, ZoneOffset.UTC);
        LocalDate toDate = LocalDate.ofInstant(TO, ZoneOffset.UTC);

        DeclineAnalysis decline = new DeclineAnalysis(
                TENANT, fromDate, "stripe", "insufficient_funds", "SOFT",
                "visa", "US", "Chase", 50, BigDecimal.valueOf(5000));
        when(repository.findDaily(eq(TENANT), eq(fromDate), eq(toDate), any(), any(), any()))
                .thenReturn(List.of(decline));

        AnalyticsQuery query = new AnalyticsQuery(FROM, TO, TimeGranularity.DAILY, List.of(), Map.of(), TENANT);
        DeclineResponse response = service.query(query);

        verify(repository).findDaily(eq(TENANT), eq(fromDate), eq(toDate), any(), any(), any());
        assertThat(response.data()).hasSize(1);
        assertThat(response.from()).isEqualTo(fromDate);
        assertThat(response.to()).isEqualTo(toDate);
    }

    @Test
    void query_groupByDeclineReason_buildsCorrectDimensions() {
        LocalDate fromDate = LocalDate.ofInstant(FROM, ZoneOffset.UTC);
        LocalDate toDate = LocalDate.ofInstant(TO, ZoneOffset.UTC);

        DeclineAnalysis decline = new DeclineAnalysis(
                TENANT, fromDate, "adyen", "expired_card", "HARD",
                "mastercard", "EU", "HSBC", 30, BigDecimal.valueOf(3000));
        when(repository.findDaily(eq(TENANT), eq(fromDate), eq(toDate), any(), any(), any()))
                .thenReturn(List.of(decline));

        AnalyticsQuery query = new AnalyticsQuery(FROM, TO, TimeGranularity.DAILY,
                List.of(AnalyticsDimension.DECLINE_REASON), Map.of(), TENANT);
        DeclineResponse response = service.query(query);

        assertThat(response.data().get(0).dimensions()).containsEntry("declineCode", "expired_card");
    }
}
