package io.nexuspay.b2b.adapter.in.rest;

import io.nexuspay.b2b.application.port.in.IssueVirtualCardUseCase;
import io.nexuspay.b2b.domain.VirtualCardStatus;
import io.nexuspay.b2b.domain.VirtualCardType;
import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.common.tenant.TenantPrincipal;
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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest slice tests for {@link VirtualCardController} (SEC-BATCH-1).
 *
 * @since SEC-BATCH-1
 */
@WebMvcTest(VirtualCardController.class)
class VirtualCardControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private IssueVirtualCardUseCase virtualCardUseCase;

    private static Authentication tenantAuth(String tenantId, String role) {
        TenantPrincipal principal = () -> tenantId;
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static IssueVirtualCardUseCase.VirtualCardResult result(String id) {
        return new IssueVirtualCardUseCase.VirtualCardResult(
                id, "4567", VirtualCardType.MULTI_USE, 500000, 0, 500000, "USD",
                VirtualCardStatus.ACTIVE, "stub", Instant.now(), Instant.now());
    }

    @Test
    void getCard_usesPrincipalTenant() throws Exception {
        when(virtualCardUseCase.getCard(eq("vc_1"), eq("tenant-1"))).thenReturn(result("vc_1"));

        mockMvc.perform(get("/v1/virtual-cards/vc_1")
                        .with(authentication(tenantAuth("tenant-1", "admin"))))
                .andExpect(status().isOk());

        verify(virtualCardUseCase).getCard("vc_1", "tenant-1");
    }

    @Test
    void freezeCard_crossTenant_invokesServiceWithCallerTenant() throws Exception {
        // SEC-BATCH-1: freezing a foreign tenant's card. Service throws not-found and is invoked with
        // the CALLER's tenant.
        doThrow(new ResourceNotFoundException("Virtual card not found"))
                .when(virtualCardUseCase).freezeCard(eq("vc_foreign"), eq("tenant-1"));

        mockMvc.perform(post("/v1/virtual-cards/vc_foreign/freeze")
                .with(authentication(tenantAuth("tenant-1", "admin"))));

        verify(virtualCardUseCase).freezeCard("vc_foreign", "tenant-1");
    }

    @Test
    void getCard_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/v1/virtual-cards/vc_1"))
                .andExpect(status().isUnauthorized());
    }
}
