package io.nexuspay.fraud.adapter.in.rest;

import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.fraud.application.dto.FraudRuleResponse;
import io.nexuspay.fraud.application.port.in.ManageFraudRulesUseCase;
import io.nexuspay.fraud.domain.model.RuleAction;
import io.nexuspay.fraud.domain.model.RuleType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest slice tests for {@link FraudRuleController} (SEC-05/06). Proves tenant is sourced from
 * the authenticated principal, not the (now-removed) X-Tenant-Id header with its dangerous
 * defaultValue="default".
 *
 * @since SEC-BATCH-1
 */
@WebMvcTest(FraudRuleController.class)
class FraudRuleControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private ManageFraudRulesUseCase ruleUseCase;

    private static final UUID RULE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static Authentication tenantAuth(String tenantId, String role) {
        TenantPrincipal principal = () -> tenantId;
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static FraudRuleResponse response() {
        return new FraudRuleResponse(RULE_ID, "rule", RuleType.VELOCITY, Map.of(), RuleAction.REVIEW,
                0, 1, 1, null, null, true, Instant.now(), Instant.now());
    }

    @Test
    void getRule_usesPrincipalTenant() throws Exception {
        when(ruleUseCase.getRule(eq(RULE_ID), eq("tenant-1"))).thenReturn(response());

        mockMvc.perform(get("/v1/fraud/rules/" + RULE_ID)
                        .with(authentication(tenantAuth("tenant-1", "admin"))))
                .andExpect(status().isOk());

        verify(ruleUseCase).getRule(RULE_ID, "tenant-1");
    }

    @Test
    void getRule_ignoresClientHeaderAndDefaultFallback() throws Exception {
        // SEC-05/06: a spoofed X-Tenant-Id must NOT override the principal tenant, and there is no
        // longer a silent defaultValue="default" fallback when the header is absent.
        when(ruleUseCase.getRule(eq(RULE_ID), eq("tenant-1"))).thenReturn(response());

        mockMvc.perform(get("/v1/fraud/rules/" + RULE_ID)
                        .header("X-Tenant-Id", "default")
                        .with(authentication(tenantAuth("tenant-1", "admin"))))
                .andExpect(status().isOk());

        verify(ruleUseCase).getRule(RULE_ID, "tenant-1");
    }

    @Test
    void getRule_crossTenant_invokesServiceWithCallerTenant() throws Exception {
        when(ruleUseCase.getRule(eq(RULE_ID), eq("tenant-1")))
                .thenThrow(new ResourceNotFoundException("Rule not found: " + RULE_ID));

        mockMvc.perform(get("/v1/fraud/rules/" + RULE_ID)
                .with(authentication(tenantAuth("tenant-1", "admin"))));

        verify(ruleUseCase).getRule(RULE_ID, "tenant-1");
    }

    @Test
    void getRule_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/v1/fraud/rules/" + RULE_ID))
                .andExpect(status().isUnauthorized());
    }
}
