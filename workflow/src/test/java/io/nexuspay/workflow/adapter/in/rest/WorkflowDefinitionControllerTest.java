package io.nexuspay.workflow.adapter.in.rest;

import io.nexuspay.workflow.application.port.in.ManageWorkflowUseCase;
import io.nexuspay.workflow.application.port.in.ManageWorkflowVersionUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * @WebMvcTest tests for {@link WorkflowDefinitionController}.
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

    @Test
    @WithMockUser(roles = "admin")
    void createWorkflow_returns201() throws Exception {
        when(workflowUseCase.createWorkflow(any())).thenReturn(sampleResult);

        mockMvc.perform(post("/v1/workflows")
                        .header("X-Tenant-Id", "tenant-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Payment Flow","description":"A workflow",
                                 "triggerType":"WEBHOOK","createdBy":"admin-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workflowId").value("wf_test123"))
                .andExpect(jsonPath("$.name").value("Payment Flow"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    @WithMockUser(roles = "admin")
    void getWorkflow_returns200() throws Exception {
        when(workflowUseCase.getWorkflow(eq("wf_test123"), any())).thenReturn(sampleResult);

        mockMvc.perform(get("/v1/workflows/wf_test123")
                        .header("X-Tenant-Id", "tenant-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value("wf_test123"))
                .andExpect(jsonPath("$.triggerType").value("WEBHOOK"));
    }

    @Test
    @WithMockUser(roles = "admin")
    void listWorkflows_returns200() throws Exception {
        when(workflowUseCase.listWorkflows("tenant-1")).thenReturn(List.of(sampleResult));

        mockMvc.perform(get("/v1/workflows")
                        .header("X-Tenant-Id", "tenant-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].workflowId").value("wf_test123"));
    }

    @Test
    @WithMockUser(roles = "admin")
    void archiveWorkflow_returns204() throws Exception {
        var archived = new ManageWorkflowUseCase.WorkflowResult(
                "wf_test123", "Payment Flow", "A workflow", "ARCHIVED", 1, "WEBHOOK",
                null, List.of(), List.of(), "admin-1", Instant.now(), Instant.now());
        when(workflowUseCase.archiveWorkflow(eq("wf_test123"), any())).thenReturn(archived);

        mockMvc.perform(post("/v1/workflows/wf_test123/archive")
                        .header("X-Tenant-Id", "tenant-1"))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "admin")
    void addNode_returns200() throws Exception {
        var withNode = new ManageWorkflowUseCase.WorkflowResult(
                "wf_test123", "Payment Flow", "A workflow", "DRAFT", 1, "WEBHOOK",
                null,
                List.of(new ManageWorkflowUseCase.NodeInfo("nd_abc", "PAYMENT", "Pay", "{}", 100.0, 200.0)),
                List.of(), "admin-1", Instant.now(), Instant.now());
        when(workflowUseCase.addNode(eq("wf_test123"), any(), any())).thenReturn(withNode);

        mockMvc.perform(post("/v1/workflows/wf_test123/nodes")
                        .header("X-Tenant-Id", "tenant-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nodeType":"PAYMENT","label":"Pay","config":"{}",
                                 "positionX":100.0,"positionY":200.0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes.length()").value(1))
                .andExpect(jsonPath("$.nodes[0].nodeType").value("PAYMENT"));
    }

    @Test
    @WithMockUser(roles = "viewer")
    void createWorkflow_forbidden_forViewer() throws Exception {
        mockMvc.perform(post("/v1/workflows")
                        .header("X-Tenant-Id", "tenant-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Test","triggerType":"MANUAL","createdBy":"user"}
                                """))
                .andExpect(status().isForbidden());
    }
}
