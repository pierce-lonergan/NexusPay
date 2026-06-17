package io.nexuspay.workflow.adapter.in.rest;

import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.workflow.application.port.in.ManageWorkflowUseCase;
import io.nexuspay.workflow.application.port.in.ManageWorkflowVersionUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static io.nexuspay.workflow.adapter.in.rest.TestAuth.authFor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SEC-27 @WebMvcTest tests for {@link WorkflowDefinitionController}.
 *
 * <p>Asserts the effective tenant is the AUTHENTICATED principal's, never a client X-Tenant-Id header,
 * and that by-id reads AND mutations are tenant-scoped — a foreign id 404s via {@code ResourceNotFoundException}
 * (no existence oracle). These FAIL on the old header-trusting controller, which passed the spoofable
 * header straight to the service (whose lookup ignored the tenant entirely — the whole-module IDOR).</p>
 *
 * @since 0.4.3 (Sprint 4.4)
 */
@WebMvcTest(WorkflowDefinitionController.class)
class WorkflowDefinitionControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private ManageWorkflowUseCase workflowUseCase;
    @MockBean private ManageWorkflowVersionUseCase versionUseCase;

    private final ManageWorkflowUseCase.WorkflowResult sampleResult =
            new ManageWorkflowUseCase.WorkflowResult(
                    "wf_test123", "Payment Flow", "A workflow", "DRAFT", 1, "WEBHOOK",
                    null, List.of(), List.of(), "admin-1", Instant.now(), Instant.now());

    // ---------- create / list / read: tenant from principal, header ignored ----------

    @Test
    void createWorkflow_returns201_usesPrincipalTenant_andIgnoresSpoofedHeader() throws Exception {
        when(workflowUseCase.createWorkflow(any())).thenReturn(sampleResult);

        mockMvc.perform(post("/v1/workflows")
                        .header("X-Tenant-Id", "victim-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Payment Flow","description":"A workflow",
                                 "triggerType":"WEBHOOK","createdBy":"admin-1"}
                                """)
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workflowId").value("wf_test123"))
                .andExpect(jsonPath("$.name").value("Payment Flow"))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        verify(workflowUseCase).createWorkflow(argThatTenant("tenant-a"));
    }

    @Test
    void getWorkflow_returns200_usesPrincipalTenant_andIgnoresSpoofedHeader() throws Exception {
        when(workflowUseCase.getWorkflow(eq("wf_test123"), eq("tenant-a"))).thenReturn(sampleResult);

        mockMvc.perform(get("/v1/workflows/wf_test123")
                        .header("X-Tenant-Id", "victim-tenant")
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value("wf_test123"))
                .andExpect(jsonPath("$.triggerType").value("WEBHOOK"));

        verify(workflowUseCase).getWorkflow("wf_test123", "tenant-a");
        verify(workflowUseCase, never()).getWorkflow("wf_test123", "victim-tenant");
    }

    @Test
    void listWorkflows_returns200_usesPrincipalTenant() throws Exception {
        when(workflowUseCase.listWorkflows("tenant-a")).thenReturn(List.of(sampleResult));

        mockMvc.perform(get("/v1/workflows")
                        .header("X-Tenant-Id", "victim-tenant")
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].workflowId").value("wf_test123"));

        verify(workflowUseCase).listWorkflows("tenant-a");
        verify(workflowUseCase, never()).listWorkflows("victim-tenant");
    }

    @Test
    void archiveWorkflow_returns204_usesPrincipalTenant() throws Exception {
        var archived = new ManageWorkflowUseCase.WorkflowResult(
                "wf_test123", "Payment Flow", "A workflow", "ARCHIVED", 1, "WEBHOOK",
                null, List.of(), List.of(), "admin-1", Instant.now(), Instant.now());
        when(workflowUseCase.archiveWorkflow(eq("wf_test123"), eq("tenant-a"))).thenReturn(archived);

        mockMvc.perform(post("/v1/workflows/wf_test123/archive")
                        .header("X-Tenant-Id", "victim-tenant")
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isNoContent());

        verify(workflowUseCase).archiveWorkflow("wf_test123", "tenant-a");
    }

    @Test
    void addNode_returns200_usesPrincipalTenant() throws Exception {
        var withNode = new ManageWorkflowUseCase.WorkflowResult(
                "wf_test123", "Payment Flow", "A workflow", "DRAFT", 1, "WEBHOOK",
                null,
                List.of(new ManageWorkflowUseCase.NodeInfo("nd_abc", "PAYMENT", "Pay", "{}", 100.0, 200.0)),
                List.of(), "admin-1", Instant.now(), Instant.now());
        when(workflowUseCase.addNode(eq("wf_test123"), eq("tenant-a"), any())).thenReturn(withNode);

        mockMvc.perform(post("/v1/workflows/wf_test123/nodes")
                        .header("X-Tenant-Id", "victim-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nodeType":"PAYMENT","label":"Pay","config":"{}",
                                 "positionX":100.0,"positionY":200.0}
                                """)
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes.length()").value(1))
                .andExpect(jsonPath("$.nodes[0].nodeType").value("PAYMENT"));

        verify(workflowUseCase).addNode(eq("wf_test123"), eq("tenant-a"), any());
        verify(workflowUseCase, never()).addNode(eq("wf_test123"), eq("victim-tenant"), any());
    }

    // ---------- by-id read: foreign id 404 via tenant-scoped service ----------

    @Test
    void getWorkflow_foreignTenant_returns404_andScopesToPrincipalTenant() throws Exception {
        // The service resolves the foreign id under the caller's tenant and 404s (no existence oracle).
        when(workflowUseCase.getWorkflow("wf_victim", "tenant-a"))
                .thenThrow(new ResourceNotFoundException("Workflow not found"));

        mockMvc.perform(get("/v1/workflows/wf_victim")
                        .header("X-Tenant-Id", "victim-tenant")
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isNotFound());

        verify(workflowUseCase).getWorkflow("wf_victim", "tenant-a");
        verify(workflowUseCase, never()).getWorkflow("wf_victim", "victim-tenant");
    }

    // ---------- by-id mutations: foreign id 404, scoped to principal tenant ----------

    @Test
    void updateWorkflow_foreignTenant_returns404_andScopesToPrincipalTenant() throws Exception {
        when(workflowUseCase.updateWorkflow(eq("wf_victim"), eq("tenant-a"), any()))
                .thenThrow(new ResourceNotFoundException("Workflow not found"));

        mockMvc.perform(put("/v1/workflows/wf_victim")
                        .header("X-Tenant-Id", "victim-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hijacked\"}")
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isNotFound());

        verify(workflowUseCase).updateWorkflow(eq("wf_victim"), eq("tenant-a"), any());
        verify(workflowUseCase, never()).updateWorkflow(eq("wf_victim"), eq("victim-tenant"), any());
    }

    @Test
    void archiveWorkflow_foreignTenant_returns404_andScopesToPrincipalTenant() throws Exception {
        when(workflowUseCase.archiveWorkflow("wf_victim", "tenant-a"))
                .thenThrow(new ResourceNotFoundException("Workflow not found"));

        mockMvc.perform(post("/v1/workflows/wf_victim/archive")
                        .header("X-Tenant-Id", "victim-tenant")
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isNotFound());

        verify(workflowUseCase).archiveWorkflow("wf_victim", "tenant-a");
    }

    @Test
    void rollbackWorkflow_foreignTenant_returns404_andScopesToPrincipalTenant() throws Exception {
        when(versionUseCase.rollbackToVersion(eq("wf_victim"), eq("tenant-a"),
                org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenThrow(new ResourceNotFoundException("Workflow not found"));

        mockMvc.perform(post("/v1/workflows/wf_victim/rollback")
                        .header("X-Tenant-Id", "victim-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetVersion\":1,\"publishedBy\":\"attacker\"}")
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isNotFound());

        verify(versionUseCase).rollbackToVersion(eq("wf_victim"), eq("tenant-a"),
                org.mockito.ArgumentMatchers.anyInt(), any());
        verify(versionUseCase, never()).rollbackToVersion(eq("wf_victim"), eq("victim-tenant"),
                org.mockito.ArgumentMatchers.anyInt(), any());
    }

    // ---------- auth gates ----------

    @Test
    void createWorkflow_forbidden_forViewer() throws Exception {
        mockMvc.perform(post("/v1/workflows")
                        .header("X-Tenant-Id", "tenant-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Test","triggerType":"MANUAL","createdBy":"user"}
                                """)
                        .with(authentication(authFor("tenant-a", "viewer"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void getWorkflow_noPrincipal_isUnauthorized() throws Exception {
        mockMvc.perform(get("/v1/workflows/wf_test123")
                        .header("X-Tenant-Id", "tenant-a"))
                .andExpect(status().isUnauthorized());
    }

    // Matches a CreateWorkflowCommand whose tenantId equals the expected (principal) tenant.
    private static ManageWorkflowUseCase.CreateWorkflowCommand argThatTenant(String expectedTenant) {
        return org.mockito.ArgumentMatchers.argThat(
                cmd -> cmd != null && expectedTenant.equals(cmd.tenantId()));
    }
}
