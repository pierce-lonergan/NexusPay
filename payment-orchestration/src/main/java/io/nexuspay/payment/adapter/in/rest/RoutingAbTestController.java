package io.nexuspay.payment.adapter.in.rest;

import io.nexuspay.payment.application.port.routing.RoutingConfigRepository;
import io.nexuspay.payment.application.port.routing.RoutingConfigRepository.RoutingConfig;
import io.nexuspay.payment.application.port.routing.RoutingDecisionRepository;
import io.nexuspay.payment.application.service.RoutingAbTestService;
import io.nexuspay.payment.application.service.RoutingAbTestService.AbTestSummary;
import io.nexuspay.payment.domain.routing.RoutingDecision;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for routing A/B test management and results.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
@RestController
@RequestMapping("/v1/routing/ab-tests")
public class RoutingAbTestController {

    private final RoutingAbTestService abTestService;
    private final RoutingConfigRepository configRepository;
    private final RoutingDecisionRepository decisionRepository;

    public RoutingAbTestController(
            RoutingAbTestService abTestService,
            RoutingConfigRepository configRepository,
            RoutingDecisionRepository decisionRepository) {
        this.abTestService = abTestService;
        this.configRepository = configRepository;
        this.decisionRepository = decisionRepository;
    }

    /**
     * Create an A/B test between two routing configurations.
     * Sets up the test config with traffic split ratio.
     */
    @PostMapping
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<Map<String, Object>> createAbTest(
            @RequestBody CreateAbTestRequest request,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {

        UUID abTestId = UUID.randomUUID();

        // Load or create the control config (A)
        RoutingConfig controlConfig = configRepository.findById(request.controlConfigId())
                .orElseThrow(() -> new IllegalArgumentException("Control config not found: " + request.controlConfigId()));

        // Create the test config (B) with traffic split
        RoutingConfig testConfig = new RoutingConfig(
                UUID.randomUUID(), tenantId, request.testConfigName(),
                request.testStrategy(),
                request.testPspList() != null ? request.testPspList() : controlConfig.pspList(),
                controlConfig.cascadeEnabled(), controlConfig.maxCascadeDepth(),
                abTestId, request.trafficSplitRatio(),
                true, java.time.Instant.now(), java.time.Instant.now()
        );

        configRepository.save(testConfig);

        return ResponseEntity.ok(Map.of(
                "ab_test_id", abTestId.toString(),
                "control_config_id", controlConfig.id().toString(),
                "control_strategy", controlConfig.strategy(),
                "test_config_id", testConfig.id().toString(),
                "test_strategy", testConfig.strategy(),
                "traffic_split_ratio", request.trafficSplitRatio(),
                "status", "ACTIVE"
        ));
    }

    /**
     * Get A/B test results summary including traffic counts and statistical readiness.
     */
    @GetMapping("/{abTestId}/summary")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<Map<String, Object>> getTestSummary(@PathVariable UUID abTestId) {
        AbTestSummary summary = abTestService.getTestSummary(abTestId);
        return ResponseEntity.ok(summaryToMap(summary));
    }

    /**
     * Get all routing decisions for an A/B test, grouped by test group.
     */
    @GetMapping("/{abTestId}/decisions")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<Map<String, Object>> getTestDecisions(@PathVariable UUID abTestId) {
        List<RoutingDecision> decisions = decisionRepository.findByAbTestId(abTestId);

        Map<String, List<Map<String, Object>>> grouped = decisions.stream()
                .collect(Collectors.groupingBy(
                        d -> d.abTestGroup() != null ? d.abTestGroup() : "UNKNOWN",
                        Collectors.mapping(this::decisionToCompactMap, Collectors.toList())
                ));

        return ResponseEntity.ok(Map.of(
                "ab_test_id", abTestId.toString(),
                "total_decisions", decisions.size(),
                "groups", grouped
        ));
    }

    /**
     * Stop an A/B test by disabling the test config.
     */
    @PostMapping("/{abTestId}/stop")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<Map<String, Object>> stopAbTest(
            @PathVariable UUID abTestId,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {

        List<RoutingConfig> configs = configRepository.findByTenantId(tenantId);
        List<RoutingConfig> testConfigs = configs.stream()
                .filter(c -> abTestId.equals(c.abTestId()))
                .toList();

        if (testConfigs.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Disable test configs (set abTestTraffic to 0)
        for (RoutingConfig config : testConfigs) {
            RoutingConfig disabled = new RoutingConfig(
                    config.id(), config.tenantId(), config.configName(),
                    config.strategy(), config.pspList(), config.cascadeEnabled(),
                    config.maxCascadeDepth(), config.abTestId(), 0.0,
                    false, config.createdAt(), java.time.Instant.now()
            );
            configRepository.save(disabled);
        }

        AbTestSummary finalSummary = abTestService.getTestSummary(abTestId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ab_test_id", abTestId.toString());
        result.put("status", "STOPPED");
        result.put("final_summary", summaryToMap(finalSummary));
        return ResponseEntity.ok(result);
    }

    // --- Helper methods ---

    private Map<String, Object> summaryToMap(AbTestSummary summary) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ab_test_id", summary.abTestId().toString());
        map.put("group_a_count", summary.groupACount());
        map.put("group_b_count", summary.groupBCount());
        map.put("total_count", summary.groupACount() + summary.groupBCount());
        map.put("has_sufficient_data", summary.hasSufficientData());
        map.put("group_a_traffic_share", summary.groupATrafficShare());
        map.put("group_a_auth_rate", summary.groupAAuthRate());
        map.put("group_b_auth_rate", summary.groupBAuthRate());
        map.put("z_score", summary.zScore());
        map.put("p_value", summary.pValue());
        map.put("confidence_interval", summary.confidenceInterval());
        map.put("is_statistically_significant", summary.isStatisticallySignificant());
        if (summary.winner() != null) {
            map.put("winner", summary.winner());
        }
        return map;
    }

    private Map<String, Object> decisionToCompactMap(RoutingDecision decision) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", decision.id().toString());
        map.put("payment_id", decision.paymentId());
        map.put("selected_psp", decision.selectedPsp());
        map.put("strategy_used", decision.strategyUsed());
        map.put("ab_test_group", decision.abTestGroup());
        map.put("decided_at", decision.decidedAt().toString());
        map.put("decision_latency_ms", decision.decisionLatencyMs());
        return map;
    }

    // --- Request DTOs ---

    record CreateAbTestRequest(
            UUID controlConfigId,
            String testConfigName,
            String testStrategy,
            List<String> testPspList,
            double trafficSplitRatio) {}
}
