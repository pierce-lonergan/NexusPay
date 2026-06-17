package io.nexuspay.workflow.adapter.in.rest;

import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.workflow.application.port.in.ExecuteWorkflowUseCase;
import io.nexuspay.workflow.domain.ExecutionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static io.nexuspay.workflow.adapter.in.rest.TestAuth.authFor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-27 @WebMvcTest tests for {@link WorkflowExecutionController}.
 *
 * <p>Asserts the effective tenant is the AUTHENTICATED principal's, never a client X-Tenant-Id header,
 * and that trigger / by-id read / cancel are tenant-scoped — a foreign id 404s (no existence oracle).</p>
 *
 * @since 0.4.3 (Sprint 4.4)
 */
@WebMvcTest(WorkflowExecutionController.class)
class WorkflowExecutionControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private ExecuteWorkflowUseCase executeUseCase;

    private final ExecuteWorkflowUseCase.ExecutionResult sampleExecution =
            new ExecuteWorkflowUseCase.ExecutionResult(
                    "wex_1", "wf_test123", 1, "temporal_wex_1", ExecutionStatus.RUNNING,
                    null, "{}", null, null, Instant.now(), null);

    @Test
    void triggerWorkflow_returns201_usesPrincipalTenant_andIgnoresSpoofedHeader() throws Exception {
        when(executeUseCase.triggerWorkflow(eq("wf_test123"), eq("tenant-a"), any()))
                .thenReturn(sampleExecution);

        mockMvc.perform(post("/v1/workflows/wf_test123/trigger")
                        .header("X-Tenant-Id", "victim-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":\"{}\"}")
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isCreated());

        verify(executeUseCase).triggerWorkflow(eq("wf_test123"), eq("tenant-a"), any());
        verify(executeUseCase, never()).triggerWorkflow(eq("wf_test123"), eq("victim-tenant"), any());
    }

    @Test
    void triggerWorkflow_foreignTenant_returns404_andScopesToPrincipalTenant() throws Exception {
        when(executeUseCase.triggerWorkflow(eq("wf_victim"), eq("tenant-a"), any()))
                .thenThrow(new ResourceNotFoundException("Workflow not found"));

        mockMvc.perform(post("/v1/workflows/wf_victim/trigger")
                        .header("X-Tenant-Id", "victim-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":\"{}\"}")
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isNotFound());

        verify(executeUseCase).triggerWorkflow(eq("wf_victim"), eq("tenant-a"), any());
    }

    @Test
    void getExecution_returns200_usesPrincipalTenant() throws Exception {
        when(executeUseCase.getExecution("wex_1", "tenant-a")).thenReturn(sampleExecution);

        mockMvc.perform(get("/v1/workflows/executions/wex_1")
                        .header("X-Tenant-Id", "victim-tenant")
                        .with(authentication(authFor("tenant-a", "viewer"))))
                .andExpect(status().isOk());

        verify(executeUseCase).getExecution("wex_1", "tenant-a");
        verify(executeUseCase, never()).getExecution("wex_1", "victim-tenant");
    }

    @Test
    void getExecution_foreignTenant_returns404_andScopesToPrincipalTenant() throws Exception {
        when(executeUseCase.getExecution("wex_victim", "tenant-a"))
                .thenThrow(new ResourceNotFoundException("Execution not found"));

        mockMvc.perform(get("/v1/workflows/executions/wex_victim")
                        .header("X-Tenant-Id", "victim-tenant")
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isNotFound());

        verify(executeUseCase).getExecution("wex_victim", "tenant-a");
    }

    @Test
    void cancelExecution_foreignTenant_returns404_andScopesToPrincipalTenant() throws Exception {
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("Execution not found"))
                .when(executeUseCase).cancelExecution("wex_victim", "tenant-a");

        mockMvc.perform(post("/v1/workflows/executions/wex_victim/cancel")
                        .header("X-Tenant-Id", "victim-tenant")
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isNotFound());

        verify(executeUseCase).cancelExecution("wex_victim", "tenant-a");
        verify(executeUseCase, never()).cancelExecution("wex_victim", "victim-tenant");
    }

    @Test
    void getExecution_noPrincipal_isUnauthorized() throws Exception {
        mockMvc.perform(get("/v1/workflows/executions/wex_1")
                        .header("X-Tenant-Id", "tenant-a"))
                .andExpect(status().isUnauthorized());
    }
}
