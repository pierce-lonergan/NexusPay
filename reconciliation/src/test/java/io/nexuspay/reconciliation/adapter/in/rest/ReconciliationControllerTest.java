package io.nexuspay.reconciliation.adapter.in.rest;

import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.reconciliation.application.port.out.ReconciliationRepository;
import io.nexuspay.reconciliation.application.service.ExceptionManagementService;
import io.nexuspay.reconciliation.application.service.ReconciliationOrchestrator;
import io.nexuspay.reconciliation.domain.MatchResult;
import io.nexuspay.reconciliation.domain.ReconciliationException;
import io.nexuspay.reconciliation.domain.ReconciliationRun;
import io.nexuspay.reconciliation.domain.SettlementRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-27 controller-slice tests for {@link ReconciliationController}.
 *
 * <p>Asserts the effective tenant is the AUTHENTICATED principal's, never a client {@code X-Tenant-Id}
 * header, and that by-id reads (getRun / getRunRecords) and mutations (resolve / assign / write-off)
 * are tenant-scoped — a foreign id 404s (no existence oracle). Also asserts the previously-unbounded
 * {@code /records} pagination is clamped. These FAIL on the old header-trusting controller, which read
 * the tenant from {@code X-Tenant-Id}, loaded runs/records/exceptions by id without a tenant predicate,
 * and returned every line of a run unbounded.</p>
 */
@WebMvcTest(ReconciliationController.class)
class ReconciliationControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private ReconciliationOrchestrator orchestrator;
    @MockBean private ExceptionManagementService exceptionService;
    @MockBean private ReconciliationRepository repository;

    private static Authentication tenantAuth(String tenantId, String role) {
        TenantPrincipal principal = () -> tenantId;
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static ReconciliationRun run(String id, String tenantId) {
        ReconciliationRun r = ReconciliationRun.create(tenantId, "stripe", "settlement.csv");
        r.setId(id);
        return r;
    }

    private static ReconciliationException exception(String id, String tenantId) {
        ReconciliationException ex = ReconciliationException.create(
                tenantId, "rec_run_1", "set_1", MatchResult.ExceptionType.MISSING_PAYMENT,
                1000L, null, "discrepancy");
        ex.setId(id);
        return ex;
    }

    private static SettlementRecord record(String id, String runId, String tenantId) {
        return new SettlementRecord(id, runId, tenantId, "stripe", "ext_1", "pay_1",
                1000L, "USD", 100L, 900L, Instant.parse("2026-01-01T00:00:00Z"));
    }

    // ---------- list / records: tenant from principal, header ignored ----------

    @Test
    void listRuns_usesPrincipalTenant_andIgnoresSpoofedHeader() throws Exception {
        when(repository.findRunsByTenant(any(), eq(20), eq(0))).thenReturn(List.of());

        mockMvc.perform(get("/v1/reconciliation/runs")
                        .header("X-Tenant-Id", "victim-tenant")
                        .with(authentication(tenantAuth("tenant-a", "operator"))))
                .andExpect(status().isOk());

        verify(repository).findRunsByTenant(eq("tenant-a"), eq(20), eq(0));
        verify(repository, never()).findRunsByTenant(eq("victim-tenant"), eq(20), eq(0));
    }

    @Test
    void listExceptions_usesPrincipalTenant_andIgnoresSpoofedHeader() throws Exception {
        when(exceptionService.listOpenExceptions(any(), eq(20), eq(0))).thenReturn(List.of());

        mockMvc.perform(get("/v1/reconciliation/exceptions")
                        .header("X-Tenant-Id", "victim-tenant")
                        .with(authentication(tenantAuth("tenant-a", "operator"))))
                .andExpect(status().isOk());

        verify(exceptionService).listOpenExceptions(eq("tenant-a"), eq(20), eq(0));
        verify(exceptionService, never()).listOpenExceptions(eq("victim-tenant"), eq(20), eq(0));
    }

    // ---------- by-id read: foreign id 404 via tenant-scoped finder ----------

    @Test
    void getRun_foreignTenantRun_returns404_andQueriesByPrincipalTenant() throws Exception {
        when(repository.findRunByIdAndTenantId("rec_run_victim", "tenant-a")).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/reconciliation/runs/{id}", "rec_run_victim")
                        .header("X-Tenant-Id", "victim-tenant")
                        .with(authentication(tenantAuth("tenant-a", "operator"))))
                .andExpect(status().isNotFound());

        verify(repository).findRunByIdAndTenantId("rec_run_victim", "tenant-a");
        verify(repository, never()).findRunById(any());
    }

    @Test
    void getRun_ownRun_returns200() throws Exception {
        when(repository.findRunByIdAndTenantId("rec_run_1", "tenant-a"))
                .thenReturn(Optional.of(run("rec_run_1", "tenant-a")));

        mockMvc.perform(get("/v1/reconciliation/runs/{id}", "rec_run_1")
                        .with(authentication(tenantAuth("tenant-a", "operator"))))
                .andExpect(status().isOk());

        verify(repository).findRunByIdAndTenantId("rec_run_1", "tenant-a");
    }

    @Test
    void getRunRecords_foreignTenantRun_returns404_andNeverFetchesRecords() throws Exception {
        when(repository.findRunByIdAndTenantId("rec_run_victim", "tenant-a")).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/reconciliation/runs/{id}/records", "rec_run_victim")
                        .header("X-Tenant-Id", "victim-tenant")
                        .with(authentication(tenantAuth("tenant-a", "operator"))))
                .andExpect(status().isNotFound());

        // Ownership is asserted BEFORE any record is read — no foreign line ever leaves the database.
        verify(repository).findRunByIdAndTenantId("rec_run_victim", "tenant-a");
        verify(repository, never()).findSettlementRecordsByRunIdAndTenantId(any(), any(), anyInt(), anyInt());
        verify(repository, never()).findSettlementRecordsByRunId(any());
    }

    @Test
    void getRunRecords_ownRun_scopesRecordsToPrincipalTenant() throws Exception {
        when(repository.findRunByIdAndTenantId("rec_run_1", "tenant-a"))
                .thenReturn(Optional.of(run("rec_run_1", "tenant-a")));
        when(repository.findSettlementRecordsByRunIdAndTenantId(eq("rec_run_1"), eq("tenant-a"), anyInt(), anyInt()))
                .thenReturn(List.of(record("set_1", "rec_run_1", "tenant-a")));

        mockMvc.perform(get("/v1/reconciliation/runs/{id}/records", "rec_run_1")
                        .header("X-Tenant-Id", "victim-tenant")
                        .with(authentication(tenantAuth("tenant-a", "operator"))))
                .andExpect(status().isOk());

        verify(repository).findSettlementRecordsByRunIdAndTenantId(eq("rec_run_1"), eq("tenant-a"), anyInt(), anyInt());
    }

    // ---------- pagination clamp on /records ----------

    @Test
    void getRunRecords_oversizedLimit_isCappedTo500() throws Exception {
        when(repository.findRunByIdAndTenantId("rec_run_1", "tenant-a"))
                .thenReturn(Optional.of(run("rec_run_1", "tenant-a")));
        when(repository.findSettlementRecordsByRunIdAndTenantId(eq("rec_run_1"), eq("tenant-a"), anyInt(), anyInt()))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/reconciliation/runs/{id}/records", "rec_run_1")
                        .param("limit", "100000")
                        .with(authentication(tenantAuth("tenant-a", "operator"))))
                .andExpect(status().isOk());

        // The unbounded client-requested 100000 must be capped to the 500 hard ceiling.
        verify(repository).findSettlementRecordsByRunIdAndTenantId("rec_run_1", "tenant-a", 500, 0);
    }

    @Test
    void getRunRecords_nonPositiveLimit_fallsBackToDefault_andNegativeOffsetFloored() throws Exception {
        when(repository.findRunByIdAndTenantId("rec_run_1", "tenant-a"))
                .thenReturn(Optional.of(run("rec_run_1", "tenant-a")));
        when(repository.findSettlementRecordsByRunIdAndTenantId(eq("rec_run_1"), eq("tenant-a"), anyInt(), anyInt()))
                .thenReturn(List.of());

        mockMvc.perform(get("/v1/reconciliation/runs/{id}/records", "rec_run_1")
                        .param("limit", "0")
                        .param("offset", "-50")
                        .with(authentication(tenantAuth("tenant-a", "operator"))))
                .andExpect(status().isOk());

        verify(repository).findSettlementRecordsByRunIdAndTenantId("rec_run_1", "tenant-a", 100, 0);
    }

    // ---------- by-id mutations: foreign id 404, scoped to principal tenant ----------

    @Test
    void resolveException_foreignTenant_returns404_andScopesToPrincipalTenant() throws Exception {
        when(exceptionService.resolve(eq("rec_exc_victim"), eq("tenant-a"), any()))
                .thenThrow(new ResourceNotFoundException("Exception not found"));

        mockMvc.perform(post("/v1/reconciliation/exceptions/{id}/resolve", "rec_exc_victim")
                        .header("X-Tenant-Id", "victim-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"ok\"}")
                        .with(authentication(tenantAuth("tenant-a", "operator"))))
                .andExpect(status().isNotFound());

        verify(exceptionService).resolve(eq("rec_exc_victim"), eq("tenant-a"), any());
        verify(exceptionService, never()).resolve(eq("rec_exc_victim"), eq("victim-tenant"), any());
    }

    @Test
    void assignException_foreignTenant_returns404_andScopesToPrincipalTenant() throws Exception {
        when(exceptionService.assign(eq("rec_exc_victim"), eq("tenant-a"), eq("user-9")))
                .thenThrow(new ResourceNotFoundException("Exception not found"));

        mockMvc.perform(post("/v1/reconciliation/exceptions/{id}/assign", "rec_exc_victim")
                        .header("X-Tenant-Id", "victim-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":\"user-9\"}")
                        .with(authentication(tenantAuth("tenant-a", "operator"))))
                .andExpect(status().isNotFound());

        verify(exceptionService).assign(eq("rec_exc_victim"), eq("tenant-a"), eq("user-9"));
        verify(exceptionService, never()).assign(eq("rec_exc_victim"), eq("victim-tenant"), any());
    }

    @Test
    void writeOffException_foreignTenant_returns404_andScopesToPrincipalTenant() throws Exception {
        when(exceptionService.writeOff(eq("rec_exc_victim"), eq("tenant-a"), any()))
                .thenThrow(new ResourceNotFoundException("Exception not found"));

        mockMvc.perform(post("/v1/reconciliation/exceptions/{id}/write-off", "rec_exc_victim")
                        .header("X-Tenant-Id", "victim-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"immaterial\"}")
                        .with(authentication(tenantAuth("tenant-a", "operator"))))
                .andExpect(status().isNotFound());

        verify(exceptionService).writeOff(eq("rec_exc_victim"), eq("tenant-a"), any());
        verify(exceptionService, never()).writeOff(eq("rec_exc_victim"), eq("victim-tenant"), any());
    }

    @Test
    void resolveException_ownTenant_returns200() throws Exception {
        when(exceptionService.resolve(eq("rec_exc_1"), eq("tenant-a"), any()))
                .thenReturn(exception("rec_exc_1", "tenant-a"));

        mockMvc.perform(post("/v1/reconciliation/exceptions/{id}/resolve", "rec_exc_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"reconciled manually\"}")
                        .with(authentication(tenantAuth("tenant-a", "operator"))))
                .andExpect(status().isOk());

        verify(exceptionService).resolve(eq("rec_exc_1"), eq("tenant-a"), any());
    }

    // ---------- authz ----------

    @Test
    void listRuns_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/v1/reconciliation/runs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void resolveException_viewer_isForbidden() throws Exception {
        mockMvc.perform(post("/v1/reconciliation/exceptions/{id}/resolve", "rec_exc_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"x\"}")
                        .with(authentication(tenantAuth("tenant-a", "viewer"))))
                .andExpect(status().isForbidden());

        // A read-only viewer must never reach the mutation service.
        verify(exceptionService, never()).resolve(any(), any(), any());
    }

    @Test
    void getRun_viewer_isAllowed() throws Exception {
        when(repository.findRunByIdAndTenantId("rec_run_1", "tenant-a"))
                .thenReturn(Optional.of(run("rec_run_1", "tenant-a")));

        mockMvc.perform(get("/v1/reconciliation/runs/{id}", "rec_run_1")
                        .with(authentication(tenantAuth("tenant-a", "viewer"))))
                .andExpect(status().isOk());
    }
}
