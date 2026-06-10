package io.nexuspay.analytics.adapter.in.rest;

import io.nexuspay.analytics.adapter.out.cache.ValkeyAnalyticsCache;
import io.nexuspay.analytics.application.dto.*;
import io.nexuspay.analytics.application.port.in.QueryAuthRatesUseCase;
import io.nexuspay.analytics.application.port.in.QueryDeclinesUseCase;
import io.nexuspay.analytics.application.port.in.QueryPspHealthUseCase;
import io.nexuspay.analytics.application.port.in.QueryRevenueUseCase;
import io.nexuspay.analytics.domain.model.TimeGranularity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AnalyticsController.class)
class AnalyticsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private QueryAuthRatesUseCase authRatesUseCase;

    @MockBean
    private QueryPspHealthUseCase pspHealthUseCase;

    @MockBean
    private QueryRevenueUseCase revenueUseCase;

    @MockBean
    private QueryDeclinesUseCase declinesUseCase;

    @MockBean
    private ValkeyAnalyticsCache cache;

    private static final String FROM = "2026-01-01T00:00:00Z";
    private static final String TO = "2026-01-15T00:00:00Z";

    @Test
    @WithMockUser(roles = "admin")
    void getAuthRates_returns200WithCorrectBody() throws Exception {
        when(cache.get(eq("auth-rates"), any(), eq(AuthRateResponse.class))).thenReturn(Optional.empty());

        AuthRateResponse response = new AuthRateResponse(
                Instant.parse(FROM), Instant.parse(TO), TimeGranularity.DAILY,
                List.of(new AuthRateResponse.AuthRateDataPoint(
                        Instant.parse(FROM), Map.of(), 1000, 950, 40, 10,
                        BigDecimal.valueOf(0.95), 120, 200
                ))
        );
        when(authRatesUseCase.query(any())).thenReturn(response);

        mockMvc.perform(get("/v1/analytics/auth-rates")
                        .param("from", FROM)
                        .param("to", TO)
                        .param("granularity", "DAILY")
                        .header("X-Tenant-Id", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granularity").value("DAILY"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].totalAttempts").value(1000));
    }

    @Test
    @WithMockUser(roles = "admin")
    void getPspHealth_returns200() throws Exception {
        when(cache.get(eq("psp-health"), any(), eq(PspHealthResponse.class))).thenReturn(Optional.empty());

        PspHealthResponse response = new PspHealthResponse(List.of(
                new PspHealthResponse.PspHealthDataPoint(
                        "stripe", Instant.parse(FROM), 92, 95, 88, 100,
                        BigDecimal.valueOf(0.95), 150, 300,
                        BigDecimal.ZERO, false, Map.of()
                )
        ));
        when(pspHealthUseCase.query(any())).thenReturn(response);

        mockMvc.perform(get("/v1/analytics/psp-health")
                        .header("X-Tenant-Id", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].pspConnector").value("stripe"))
                .andExpect(jsonPath("$.data[0].healthScore").value(92));
    }

    @Test
    @WithMockUser(roles = "admin")
    void getRevenue_returns200() throws Exception {
        when(cache.get(eq("revenue"), any(), eq(RevenueResponse.class))).thenReturn(Optional.empty());

        RevenueResponse response = new RevenueResponse(
                Instant.parse(FROM), Instant.parse(TO), TimeGranularity.DAILY,
                List.of(new RevenueResponse.RevenueDataPoint(
                        Instant.parse(FROM), Map.of(),
                        BigDecimal.valueOf(100000), 500,
                        BigDecimal.valueOf(2500), BigDecimal.valueOf(97500),
                        BigDecimal.valueOf(1000), 5,
                        BigDecimal.valueOf(500), 2
                ))
        );
        when(revenueUseCase.query(any())).thenReturn(response);

        mockMvc.perform(get("/v1/analytics/revenue")
                        .param("from", FROM)
                        .param("to", TO)
                        .param("granularity", "DAILY")
                        .header("X-Tenant-Id", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granularity").value("DAILY"))
                .andExpect(jsonPath("$.data[0].totalCount").value(500));
    }

    @Test
    @WithMockUser(roles = "admin")
    void getDeclines_returns200() throws Exception {
        when(cache.get(eq("declines"), any(), eq(DeclineResponse.class))).thenReturn(Optional.empty());

        DeclineResponse response = new DeclineResponse(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 15),
                List.of(new DeclineResponse.DeclineDataPoint(
                        LocalDate.of(2026, 1, 1), Map.of(),
                        "insufficient_funds", "SOFT", 50, BigDecimal.valueOf(5000)
                ))
        );
        when(declinesUseCase.query(any())).thenReturn(response);

        mockMvc.perform(get("/v1/analytics/declines")
                        .param("from", FROM)
                        .param("to", TO)
                        .header("X-Tenant-Id", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].declineCode").value("insufficient_funds"))
                .andExpect(jsonPath("$.data[0].declineCategory").value("SOFT"));
    }

    @Test
    @WithMockUser(roles = "admin")
    void getAuthRates_groupByParameter_parsesCommaSeparatedDimensions() throws Exception {
        when(cache.get(eq("auth-rates"), any(), eq(AuthRateResponse.class))).thenReturn(Optional.empty());

        AuthRateResponse response = new AuthRateResponse(
                Instant.parse(FROM), Instant.parse(TO), TimeGranularity.DAILY, List.of());
        when(authRatesUseCase.query(any(AnalyticsQuery.class))).thenAnswer(invocation -> {
            AnalyticsQuery q = invocation.getArgument(0);
            // Verify the groupBy was parsed from "PSP,CARD_BRAND" into a list
            assert q.groupBy().size() == 2;
            return response;
        });

        mockMvc.perform(get("/v1/analytics/auth-rates")
                        .param("from", FROM)
                        .param("to", TO)
                        .param("groupBy", "PSP,CARD_BRAND")
                        .header("X-Tenant-Id", "default"))
                .andExpect(status().isOk());

        verify(authRatesUseCase).query(any(AnalyticsQuery.class));
    }

    @Test
    @WithMockUser(roles = "admin")
    void getAuthRates_cacheHit_servesFromCache() throws Exception {
        AuthRateResponse cachedResponse = new AuthRateResponse(
                Instant.parse(FROM), Instant.parse(TO), TimeGranularity.DAILY,
                List.of(new AuthRateResponse.AuthRateDataPoint(
                        Instant.parse(FROM), Map.of(), 500, 480, 15, 5,
                        BigDecimal.valueOf(0.96), 100, 180
                ))
        );
        when(cache.get(eq("auth-rates"), any(), eq(AuthRateResponse.class)))
                .thenReturn(Optional.of(cachedResponse));

        mockMvc.perform(get("/v1/analytics/auth-rates")
                        .param("from", FROM)
                        .param("to", TO)
                        .header("X-Tenant-Id", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].totalAttempts").value(500));

        // The use case should NOT have been called because cache returned a hit
        verify(authRatesUseCase, org.mockito.Mockito.never()).query(any());
    }
}
