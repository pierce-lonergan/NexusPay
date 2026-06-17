package io.nexuspay.billing.adapter.in.rest;

import io.nexuspay.billing.application.service.SubscriptionLifecycleService;
import io.nexuspay.billing.domain.Subscription;
import io.nexuspay.billing.domain.SubscriptionState;
import io.nexuspay.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static io.nexuspay.billing.adapter.in.rest.TestAuth.authFor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-26 controller-slice tests for {@link SubscriptionController}.
 *
 * <p>Asserts the effective tenant is the AUTHENTICATED principal's, never a client X-Tenant-Id header,
 * and that by-id reads AND mutations (cancel/pause/resume/changePlan) are tenant-scoped — a foreign id
 * 404s (no existence oracle). These FAIL on the old header-trusting controller: {@code list} used the
 * header, and the mutations called the service WITHOUT a tenant, so the service's unscoped
 * {@code getOrThrow(id)} would mutate the victim's subscription.</p>
 */
@WebMvcTest(SubscriptionController.class)
class SubscriptionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SubscriptionLifecycleService lifecycleService;

    private static Subscription minimalSub() {
        Subscription s = new Subscription();
        s.setId("sub_1");
        s.setTenantId("tenant-a");
        s.setCustomerId("cus_1");
        s.setPriceId("price_1");
        s.setStatus(SubscriptionState.ACTIVE);
        s.setQuantity(1);
        s.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return s;
    }

    // ---------- list / create: tenant from principal, header ignored ----------

    @Test
    void list_usesPrincipalTenant_andIgnoresSpoofedHeader() throws Exception {
        when(lifecycleService.listByTenant(any(), eq(20), eq(0))).thenReturn(List.of());

        mockMvc.perform(get("/v1/subscriptions")
                        .header("X-Tenant-Id", "victim-tenant")
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isOk());

        verify(lifecycleService).listByTenant(eq("tenant-a"), eq(20), eq(0));
        verify(lifecycleService, never()).listByTenant(eq("victim-tenant"), eq(20), eq(0));
    }

    @Test
    void create_usesPrincipalTenant_andIgnoresSpoofedHeader() throws Exception {
        when(lifecycleService.createSubscription(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any())).thenReturn(minimalSub());

        mockMvc.perform(post("/v1/subscriptions")
                        .header("X-Tenant-Id", "victim-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"cus_1\",\"priceId\":\"price_1\"}")
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isCreated());

        verify(lifecycleService).createSubscription(eq("tenant-a"), eq("cus_1"), eq("price_1"),
                eq(1), any(), any());
    }

    // ---------- by-id read: foreign id 404 via tenant-scoped finder ----------

    @Test
    void get_foreignTenantSubscription_returns404_andQueriesByPrincipalTenant() throws Exception {
        when(lifecycleService.findById("sub_victim", "tenant-a")).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/subscriptions/{id}", "sub_victim")
                        .header("X-Tenant-Id", "victim-tenant")
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isNotFound());

        verify(lifecycleService).findById("sub_victim", "tenant-a");
    }

    // ---------- by-id mutations: foreign id 404, scoped to principal tenant ----------

    @Test
    void cancel_foreignTenantSubscription_returns404_andScopesToPrincipalTenant() throws Exception {
        when(lifecycleService.cancel(eq("sub_victim"), eq("tenant-a"), anyBoolean()))
                .thenThrow(new ResourceNotFoundException("Subscription not found"));

        mockMvc.perform(post("/v1/subscriptions/{id}/cancel", "sub_victim")
                        .header("X-Tenant-Id", "victim-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"at_period_end\":true}")
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isNotFound());

        verify(lifecycleService).cancel(eq("sub_victim"), eq("tenant-a"), anyBoolean());
        // Must never act under the spoofed header tenant.
        verify(lifecycleService, never()).cancel(eq("sub_victim"), eq("victim-tenant"), anyBoolean());
    }

    @Test
    void pause_foreignTenantSubscription_returns404_andScopesToPrincipalTenant() throws Exception {
        when(lifecycleService.pause("sub_victim", "tenant-a"))
                .thenThrow(new ResourceNotFoundException("Subscription not found"));

        mockMvc.perform(post("/v1/subscriptions/{id}/pause", "sub_victim")
                        .header("X-Tenant-Id", "victim-tenant")
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isNotFound());

        verify(lifecycleService).pause("sub_victim", "tenant-a");
    }

    @Test
    void resume_foreignTenantSubscription_returns404_andScopesToPrincipalTenant() throws Exception {
        when(lifecycleService.resume("sub_victim", "tenant-a"))
                .thenThrow(new ResourceNotFoundException("Subscription not found"));

        mockMvc.perform(post("/v1/subscriptions/{id}/resume", "sub_victim")
                        .header("X-Tenant-Id", "victim-tenant")
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isNotFound());

        verify(lifecycleService).resume("sub_victim", "tenant-a");
    }

    @Test
    void changePlan_foreignTenantSubscription_returns404_andScopesToPrincipalTenant() throws Exception {
        when(lifecycleService.changePlan("sub_victim", "tenant-a", "price_2"))
                .thenThrow(new ResourceNotFoundException("Subscription not found"));

        mockMvc.perform(post("/v1/subscriptions/{id}/change", "sub_victim")
                        .header("X-Tenant-Id", "victim-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price_id\":\"price_2\"}")
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isNotFound());

        verify(lifecycleService).changePlan("sub_victim", "tenant-a", "price_2");
    }

    @Test
    void list_noPrincipal_isUnauthorized() throws Exception {
        mockMvc.perform(get("/v1/subscriptions")
                        .header("X-Tenant-Id", "default"))
                .andExpect(status().isUnauthorized());
    }
}
