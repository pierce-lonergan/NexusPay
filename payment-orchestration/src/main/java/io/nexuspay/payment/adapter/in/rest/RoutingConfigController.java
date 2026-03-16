package io.nexuspay.payment.adapter.in.rest;

import io.nexuspay.payment.application.port.routing.PspFeeRepository;
import io.nexuspay.payment.application.port.routing.PspHealthRepository;
import io.nexuspay.payment.application.port.routing.RoutingConfigRepository;
import io.nexuspay.payment.application.port.routing.RoutingConfigRepository.RoutingConfig;
import io.nexuspay.payment.application.port.routing.RoutingDecisionRepository;
import io.nexuspay.payment.application.port.routing.RoutePaymentUseCase;
import io.nexuspay.payment.domain.routing.PspFeeModel;
import io.nexuspay.payment.domain.routing.PspHealthSnapshot;
import io.nexuspay.payment.domain.routing.RoutingContext;
import io.nexuspay.payment.domain.routing.RoutingDecision;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * REST controller for smart routing configuration and operations.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
@RestController
@RequestMapping("/v1/routing")
public class RoutingConfigController {

    private final RoutingConfigRepository configRepository;
    private final RoutingDecisionRepository decisionRepository;
    private final PspFeeRepository feeRepository;
    private final PspHealthRepository healthRepository;
    private final RoutePaymentUseCase routePaymentUseCase;

    public RoutingConfigController(
            RoutingConfigRepository configRepository,
            RoutingDecisionRepository decisionRepository,
            PspFeeRepository feeRepository,
            PspHealthRepository healthRepository,
            RoutePaymentUseCase routePaymentUseCase) {
        this.configRepository = configRepository;
        this.decisionRepository = decisionRepository;
        this.feeRepository = feeRepository;
        this.healthRepository = healthRepository;
        this.routePaymentUseCase = routePaymentUseCase;
    }

    // --- Routing Config CRUD ---

    /**
     * Create a new routing configuration for a tenant.
     */
    @PostMapping("/configs")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<Map<String, Object>> createConfig(
            @RequestBody CreateConfigRequest request,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {

        RoutingConfig config = new RoutingConfig(
                UUID.randomUUID(), tenantId, request.configName(), request.strategy(),
                request.pspList() != null ? request.pspList() : List.of(),
                request.cascadeEnabled(), request.maxCascadeDepth(),
                request.abTestId(), request.abTestTraffic() != null ? request.abTestTraffic() : 0.0,
                true, java.time.Instant.now(), java.time.Instant.now()
        );

        RoutingConfig saved = configRepository.save(config);
        return ResponseEntity.ok(configToMap(saved));
    }

    /**
     * Get routing config by ID.
     */
    @GetMapping("/configs/{configId}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<Map<String, Object>> getConfig(@PathVariable UUID configId) {
        return configRepository.findById(configId)
                .map(c -> ResponseEntity.ok(configToMap(c)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get the active routing config for a tenant.
     */
    @GetMapping("/configs/active")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<Map<String, Object>> getActiveConfig(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return configRepository.findActiveByTenant(tenantId)
                .map(c -> ResponseEntity.ok(configToMap(c)))
                .orElse(ResponseEntity.ok(configToMap(RoutingConfig.defaults(tenantId))));
    }

    /**
     * List all routing configs for a tenant.
     */
    @GetMapping("/configs")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<List<Map<String, Object>>> listConfigs(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        List<Map<String, Object>> configs = configRepository.findByTenantId(tenantId).stream()
                .map(this::configToMap)
                .toList();
        return ResponseEntity.ok(configs);
    }

    // --- Routing Decisions (audit trail) ---

    /**
     * Get a routing decision by ID.
     */
    @GetMapping("/decisions/{decisionId}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<Map<String, Object>> getDecision(@PathVariable UUID decisionId) {
        return decisionRepository.findById(decisionId)
                .map(d -> ResponseEntity.ok(decisionToMap(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get routing decision for a specific payment.
     */
    @GetMapping("/decisions/by-payment/{paymentId}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<Map<String, Object>> getDecisionByPayment(@PathVariable String paymentId) {
        return decisionRepository.findByPaymentId(paymentId)
                .map(d -> ResponseEntity.ok(decisionToMap(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    // --- PSP Fee Models ---

    /**
     * List fee models for a tenant.
     */
    @GetMapping("/fees")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<List<Map<String, Object>>> listFees(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam(required = false) String currency) {

        List<PspFeeModel> fees = currency != null
                ? feeRepository.findByTenantAndCurrency(tenantId, currency.toUpperCase())
                : feeRepository.findByTenantId(tenantId);

        return ResponseEntity.ok(fees.stream().map(this::feeToMap).toList());
    }

    /**
     * Create a fee model for a PSP.
     */
    @PostMapping("/fees")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<Map<String, Object>> createFee(
            @RequestBody CreateFeeRequest request,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {

        PspFeeModel fee = new PspFeeModel(
                UUID.randomUUID(), tenantId, request.pspConnector(),
                PspFeeModel.FeeType.valueOf(request.feeType()),
                request.perTxFee(), request.percentageFee(),
                request.interchangeMarkupBps() != null ? request.interchangeMarkupBps() : 0,
                request.schemeFeeBps() != null ? request.schemeFeeBps() : 0,
                request.currency().toUpperCase(),
                request.effectiveFrom() != null ? request.effectiveFrom() : LocalDate.now(),
                request.effectiveTo()
        );

        PspFeeModel saved = feeRepository.save(fee);
        return ResponseEntity.ok(feeToMap(saved));
    }

    // --- PSP Health ---

    /**
     * Get health snapshot for a specific PSP.
     */
    @GetMapping("/health/{pspConnector}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<?> getPspHealth(@PathVariable String pspConnector) {
        return healthRepository.getHealth(pspConnector)
                .map(h -> ResponseEntity.ok(healthToMap(pspConnector, h)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get health snapshots for all PSPs.
     */
    @GetMapping("/health")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<List<Map<String, Object>>> getAllPspHealth() {
        Map<String, PspHealthSnapshot> allHealth = healthRepository.getAllHealth();
        List<Map<String, Object>> result = allHealth.entrySet().stream()
                .map(e -> healthToMap(e.getKey(), e.getValue()))
                .toList();
        return ResponseEntity.ok(result);
    }

    // --- Dry-run route simulation ---

    /**
     * Simulate a routing decision without executing payment.
     * Useful for testing routing configuration changes.
     */
    @PostMapping("/simulate")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<Map<String, Object>> simulateRoute(
            @RequestBody SimulateRouteRequest request,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {

        RoutingContext context = new RoutingContext(
                tenantId, "simulate_" + UUID.randomUUID(),
                request.amount(), request.currency(),
                request.cardBrand(), request.cardType(),
                request.issuingCountry(), request.ipCountry(),
                true, Map.of("simulation", "true")
        );

        RoutingDecision decision = routePaymentUseCase.route(context);
        Map<String, Object> result = new LinkedHashMap<>(decisionToMap(decision));
        result.put("simulated", true);
        return ResponseEntity.ok(result);
    }

    // --- Helper methods ---

    private Map<String, Object> configToMap(RoutingConfig config) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", config.id().toString());
        map.put("tenant_id", config.tenantId());
        map.put("config_name", config.configName());
        map.put("strategy", config.strategy());
        map.put("psp_list", config.pspList());
        map.put("cascade_enabled", config.cascadeEnabled());
        map.put("max_cascade_depth", config.maxCascadeDepth());
        if (config.abTestId() != null) {
            map.put("ab_test_id", config.abTestId().toString());
            map.put("ab_test_traffic", config.abTestTraffic());
        }
        map.put("enabled", config.enabled());
        map.put("created_at", config.createdAt().toString());
        map.put("updated_at", config.updatedAt().toString());
        return map;
    }

    private Map<String, Object> decisionToMap(RoutingDecision decision) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", decision.id().toString());
        map.put("tenant_id", decision.tenantId());
        map.put("payment_id", decision.paymentId());
        map.put("strategy_used", decision.strategyUsed());
        map.put("config_id", decision.configId().toString());
        map.put("selected_psp", decision.selectedPsp());
        map.put("candidate_scores", decision.candidateScores());
        map.put("cascade_order", decision.cascadeOrder());
        if (decision.abTestId() != null) {
            map.put("ab_test_id", decision.abTestId().toString());
            map.put("ab_test_group", decision.abTestGroup());
        }
        map.put("decided_at", decision.decidedAt().toString());
        map.put("decision_latency_ms", decision.decisionLatencyMs());
        return map;
    }

    private Map<String, Object> feeToMap(PspFeeModel fee) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", fee.id().toString());
        map.put("psp_connector", fee.pspConnector());
        map.put("fee_type", fee.feeType().name());
        map.put("per_tx_fee", fee.perTxFee());
        map.put("percentage_fee", fee.percentageFee());
        map.put("interchange_markup_bps", fee.interchangeMarkupBps());
        map.put("scheme_fee_bps", fee.schemeFeeBps());
        map.put("currency", fee.currency());
        map.put("effective_from", fee.effectiveFrom().toString());
        if (fee.effectiveTo() != null) map.put("effective_to", fee.effectiveTo().toString());
        return map;
    }

    private Map<String, Object> healthToMap(String psp, PspHealthSnapshot health) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("psp_connector", psp);
        map.put("auth_rate", health.authRate());
        map.put("total_transactions", health.totalTransactions());
        map.put("latency_p50_ms", health.latencyP50Ms());
        map.put("latency_p95_ms", health.latencyP95Ms());
        map.put("latency_p99_ms", health.latencyP99Ms());
        map.put("circuit_breaker_open", health.circuitBreakerOpen());
        map.put("healthy", health.isHealthy());
        map.put("sufficient_data", health.hasSufficientData());
        return map;
    }

    // --- Request DTOs ---

    record CreateConfigRequest(
            String configName, String strategy, List<String> pspList,
            boolean cascadeEnabled, int maxCascadeDepth,
            UUID abTestId, Double abTestTraffic) {}

    record CreateFeeRequest(
            String pspConnector, String feeType, BigDecimal perTxFee,
            BigDecimal percentageFee, Integer interchangeMarkupBps,
            Integer schemeFeeBps, String currency,
            LocalDate effectiveFrom, LocalDate effectiveTo) {}

    record SimulateRouteRequest(
            BigDecimal amount, String currency, String cardBrand,
            String cardType, String issuingCountry, String ipCountry) {}
}
