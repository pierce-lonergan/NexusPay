package io.nexuspay.payment.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.common.tenant.LiveModePrincipal;
import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.payment.application.service.customer.CustomerService;
import io.nexuspay.payment.domain.customer.Customer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TEST-3a: pins the CustomerController's tenant + livemode derivation and the no-tenant-in-body
 * guarantee, via direct controller instantiation with a mocked service (no Spring). Mirrors
 * {@code DisputeTestControllerTest}.
 *
 * <ul>
 *   <li>create under a TEST principal (live=false) -> service.create(..., livemode=false), 201, body
 *       has a cus_ id + livemode=false.</li>
 *   <li>create under a LIVE principal (live=true) -> service.create(..., livemode=true).</li>
 *   <li>retrieve returns 404 when service.findById is empty (no oracle).</li>
 *   <li>the response body NEVER contains the tenant.</li>
 * </ul>
 */
class CustomerControllerTenantLivemodeTest {

    private static final String TENANT = "t1";

    private final CustomerService service = mock(CustomerService.class);
    private final CustomerController controller = new CustomerController(service);

    /** A minimal common-portable principal carrying tenant + live mode (no iam dependency). */
    private record TestPrincipal(String tenant, boolean live)
            implements TenantPrincipal, LiveModePrincipal {
        @Override public String tenantId() { return tenant; }
        @Override public boolean live() { return live; }
    }

    private void authenticateAs(String tenant, boolean live) {
        var auth = new UsernamePasswordAuthenticationToken(
                new TestPrincipal(tenant, live), "n/a", java.util.List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private Customer customer(boolean livemode) {
        // a server-built Customer the service would return (id minted via the factory).
        Customer c = Customer.create(TENANT, livemode, "jane@example.com", "Jane", "desc",
                Map.of("k", "v"));
        return c;
    }

    @Test
    void createUnderTestKey_stampsLivemodeFalse_and201() {
        authenticateAs(TENANT, false); // TEST key
        when(service.create(eq(TENANT), eq(false), any(), any(), any(), any()))
                .thenReturn(customer(false));

        ResponseEntity<CustomerController.CustomerResponse> resp = controller.createCustomer(
                new CustomerController.CreateCustomerRequest("jane@example.com", "Jane", "desc", Map.of("k", "v")));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // created UNDER the caller tenant, livemode=false (TEST), from CallerMode — never the body.
        verify(service).create(eq(TENANT), eq(false), eq("jane@example.com"), eq("Jane"), eq("desc"), any());
        CustomerController.CustomerResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.id()).startsWith("cus_");
        assertThat(body.livemode()).isFalse();
        assertThat(body.object()).isEqualTo("customer");
    }

    @Test
    void createUnderLiveKey_stampsLivemodeTrue() {
        authenticateAs(TENANT, true); // LIVE key
        when(service.create(eq(TENANT), eq(true), any(), any(), any(), any()))
                .thenReturn(customer(true));

        ResponseEntity<CustomerController.CustomerResponse> resp = controller.createCustomer(
                new CustomerController.CreateCustomerRequest(null, null, null, null));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(service).create(eq(TENANT), eq(true), any(), any(), any(), any());
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().livemode()).isTrue();
    }

    @Test
    void retrieveReturns404WhenServiceEmpty_noOracle() {
        authenticateAs(TENANT, false);
        when(service.findById(eq("cus_victim"), eq(TENANT))).thenReturn(java.util.Optional.empty());

        ResponseEntity<CustomerController.CustomerResponse> resp = controller.getCustomer("cus_victim");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(service).findById("cus_victim", TENANT);
    }

    @Test
    void responseBodyNeverContainsTenant() throws Exception {
        authenticateAs(TENANT, false);
        when(service.create(eq(TENANT), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(customer(false));

        ResponseEntity<CustomerController.CustomerResponse> resp = controller.createCustomer(
                new CustomerController.CreateCustomerRequest("jane@example.com", "Jane", "desc", Map.of("k", "v")));

        // L-071: Customer carries Instant-derived time + a metadata Map -> findAndRegisterModules so the
        // jsr310/epoch serialization does not throw and fail the whole class.
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String json = mapper.writeValueAsString(resp.getBody());

        assertThat(json).doesNotContain("tenant");
        assertThat(json).doesNotContain(TENANT);
        // sanity: it DID serialize the customer fields
        assertThat(json).contains("\"object\":\"customer\"");
        assertThat(json).contains("cus_");
    }
}
