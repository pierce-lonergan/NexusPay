package io.nexuspay.fraud.adapter.in.rest;

import io.nexuspay.common.tenant.CallerTenant;
import io.nexuspay.fraud.application.dto.FraudRuleCreateRequest;
import io.nexuspay.fraud.application.dto.FraudRuleResponse;
import io.nexuspay.fraud.application.dto.FraudRuleUpdateRequest;
import io.nexuspay.fraud.application.port.in.ManageFraudRulesUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for fraud rule CRUD operations.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
@RestController
@RequestMapping("/v1/fraud/rules")
public class FraudRuleController {

    private final ManageFraudRulesUseCase ruleUseCase;

    public FraudRuleController(ManageFraudRulesUseCase ruleUseCase) {
        this.ruleUseCase = ruleUseCase;
    }

    @PostMapping
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<FraudRuleResponse> createRule(
            @RequestBody FraudRuleCreateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        // SEC-05/06: tenant resolved from the authenticated principal, never from a client header
        // (the old defaultValue="default" silently collapsed an absent header to the "default" tenant).
        // The Jwt is retained ONLY for the createdBy audit field — not for authority.
        String createdBy = jwt != null ? jwt.getSubject() : "system";
        FraudRuleResponse response = ruleUseCase.createRule(request, CallerTenant.require(), createdBy);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{ruleId}")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<FraudRuleResponse> updateRule(
            @PathVariable UUID ruleId,
            @RequestBody FraudRuleUpdateRequest request) {
        return ResponseEntity.ok(ruleUseCase.updateRule(ruleId, request, CallerTenant.require()));
    }

    @PostMapping("/{ruleId}/disable")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<Void> disableRule(
            @PathVariable UUID ruleId) {
        ruleUseCase.disableRule(ruleId, CallerTenant.require());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{ruleId}/enable")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<Void> enableRule(
            @PathVariable UUID ruleId) {
        ruleUseCase.enableRule(ruleId, CallerTenant.require());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{ruleId}")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<FraudRuleResponse> getRule(
            @PathVariable UUID ruleId) {
        return ResponseEntity.ok(ruleUseCase.getRule(ruleId, CallerTenant.require()));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<List<FraudRuleResponse>> listRules() {
        return ResponseEntity.ok(ruleUseCase.listRules(CallerTenant.require()));
    }
}
