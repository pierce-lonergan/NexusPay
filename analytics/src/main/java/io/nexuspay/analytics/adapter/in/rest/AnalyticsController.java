package io.nexuspay.analytics.adapter.in.rest;

import io.nexuspay.analytics.adapter.out.cache.ValkeyAnalyticsCache;
import io.nexuspay.analytics.application.dto.*;
import io.nexuspay.analytics.application.port.in.QueryAuthRatesUseCase;
import io.nexuspay.analytics.application.port.in.QueryDeclinesUseCase;
import io.nexuspay.analytics.application.port.in.QueryPspHealthUseCase;
import io.nexuspay.analytics.application.port.in.QueryRevenueUseCase;
import io.nexuspay.analytics.domain.model.AnalyticsDimension;
import io.nexuspay.analytics.domain.model.TimeGranularity;
import io.nexuspay.common.tenant.CallerTenant;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for analytics API endpoints.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
@RestController
@RequestMapping("/v1/analytics")
public class AnalyticsController {

    private final QueryAuthRatesUseCase authRatesUseCase;
    private final QueryPspHealthUseCase pspHealthUseCase;
    private final QueryRevenueUseCase revenueUseCase;
    private final QueryDeclinesUseCase declinesUseCase;
    private final ValkeyAnalyticsCache cache;

    public AnalyticsController(QueryAuthRatesUseCase authRatesUseCase,
                                QueryPspHealthUseCase pspHealthUseCase,
                                QueryRevenueUseCase revenueUseCase,
                                QueryDeclinesUseCase declinesUseCase,
                                ValkeyAnalyticsCache cache) {
        this.authRatesUseCase = authRatesUseCase;
        this.pspHealthUseCase = pspHealthUseCase;
        this.revenueUseCase = revenueUseCase;
        this.declinesUseCase = declinesUseCase;
        this.cache = cache;
    }

    @GetMapping("/auth-rates")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<AuthRateResponse> getAuthRates(
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false, defaultValue = "DAILY") TimeGranularity granularity,
            @RequestParam(required = false) String psp,
            @RequestParam(required = false) String cardBrand,
            @RequestParam(required = false) String currency) {

        // SEC-26: tenant resolved from the authenticated principal, never from a client X-Tenant-Id
        // header (the old defaultValue="default" silently collapsed an absent header to "default").
        String tenantId = CallerTenant.require();

        String queryHash = ValkeyAnalyticsCache.hashQuery(
                "auth-rates", from.toString(), to.toString(), groupBy,
                granularity.name(), psp, cardBrand, currency, tenantId);

        Optional<AuthRateResponse> cached = cache.get("auth-rates", queryHash, AuthRateResponse.class);
        if (cached.isPresent()) return ResponseEntity.ok(cached.get());

        AnalyticsQuery query = new AnalyticsQuery(from, to, granularity,
                parseDimensions(groupBy), buildFilters(psp, cardBrand, currency, null), tenantId);

        AuthRateResponse response = authRatesUseCase.query(query);
        cache.put("auth-rates", queryHash, response);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/psp-health")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<PspHealthResponse> getPspHealth(
            @RequestParam(required = false) String psp) {

        // SEC-26: tenant resolved from the authenticated principal, never from a client X-Tenant-Id header.
        String tenantId = CallerTenant.require();

        String queryHash = ValkeyAnalyticsCache.hashQuery("psp-health", psp, tenantId);

        Optional<PspHealthResponse> cached = cache.get("psp-health", queryHash, PspHealthResponse.class);
        if (cached.isPresent()) return ResponseEntity.ok(cached.get());

        AnalyticsQuery query = new AnalyticsQuery(null, null, null,
                List.of(), buildFilters(psp, null, null, null), tenantId);

        PspHealthResponse response = pspHealthUseCase.query(query);
        cache.put("psp-health", queryHash, response);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/revenue")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<RevenueResponse> getRevenue(
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false, defaultValue = "DAILY") TimeGranularity granularity,
            @RequestParam(required = false) String currency) {

        // SEC-26: tenant resolved from the authenticated principal, never from a client X-Tenant-Id header.
        String tenantId = CallerTenant.require();

        String queryHash = ValkeyAnalyticsCache.hashQuery(
                "revenue", from.toString(), to.toString(), groupBy,
                granularity.name(), currency, tenantId);

        Optional<RevenueResponse> cached = cache.get("revenue", queryHash, RevenueResponse.class);
        if (cached.isPresent()) return ResponseEntity.ok(cached.get());

        AnalyticsQuery query = new AnalyticsQuery(from, to, granularity,
                parseDimensions(groupBy), buildFilters(null, null, currency, null), tenantId);

        RevenueResponse response = revenueUseCase.query(query);
        cache.put("revenue", queryHash, response);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/declines")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<DeclineResponse> getDeclines(
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) String psp,
            @RequestParam(required = false) String declineCode) {

        // SEC-26: tenant resolved from the authenticated principal, never from a client X-Tenant-Id header.
        String tenantId = CallerTenant.require();

        String queryHash = ValkeyAnalyticsCache.hashQuery(
                "declines", from.toString(), to.toString(), groupBy, psp, declineCode, tenantId);

        Optional<DeclineResponse> cached = cache.get("declines", queryHash, DeclineResponse.class);
        if (cached.isPresent()) return ResponseEntity.ok(cached.get());

        AnalyticsQuery query = new AnalyticsQuery(from, to, TimeGranularity.DAILY,
                parseDimensions(groupBy), buildFilters(psp, null, null, declineCode), tenantId);

        DeclineResponse response = declinesUseCase.query(query);
        cache.put("declines", queryHash, response);
        return ResponseEntity.ok(response);
    }

    private List<AnalyticsDimension> parseDimensions(String groupBy) {
        if (groupBy == null || groupBy.isBlank()) return List.of();
        return Arrays.stream(groupBy.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .map(AnalyticsDimension::valueOf)
                .collect(Collectors.toList());
    }

    private Map<String, String> buildFilters(String psp, String cardBrand, String currency, String declineCode) {
        Map<String, String> filters = new HashMap<>();
        if (psp != null) filters.put("psp", psp);
        if (cardBrand != null) filters.put("cardBrand", cardBrand);
        if (currency != null) filters.put("currency", currency);
        if (declineCode != null) filters.put("declineCode", declineCode);
        return filters;
    }
}
