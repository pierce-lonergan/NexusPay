package io.nexuspay.billing.adapter.in.rest;

import io.nexuspay.billing.application.port.out.ProductRepository;
import io.nexuspay.billing.domain.Product;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static io.nexuspay.billing.adapter.in.rest.TestAuth.authFor;
import static org.assertj.core.api.Assertions.assertThat;
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
 * SEC-26 controller-slice tests for {@link ProductController}.
 *
 * <p>Asserts the effective tenant is the AUTHENTICATED principal's, never a client X-Tenant-Id header.
 * These FAIL on the old header-trusting controller, which would create/list under "victim-tenant".</p>
 */
@WebMvcTest(ProductController.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductRepository productRepository;

    @Test
    void listProducts_usesPrincipalTenant_andIgnoresSpoofedHeader() throws Exception {
        when(productRepository.findProductsByTenant(any(), eq(20), eq(0))).thenReturn(List.of());

        mockMvc.perform(get("/v1/products")
                        .header("X-Tenant-Id", "victim-tenant")
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isOk());

        verify(productRepository).findProductsByTenant(eq("tenant-a"), eq(20), eq(0));
        verify(productRepository, never()).findProductsByTenant(eq("victim-tenant"), eq(20), eq(0));
    }

    @Test
    void listPrices_usesPrincipalTenant_andIgnoresSpoofedHeader() throws Exception {
        when(productRepository.findPricesByTenant(any(), eq(20), eq(0))).thenReturn(List.of());

        mockMvc.perform(get("/v1/prices")
                        .header("X-Tenant-Id", "victim-tenant")
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isOk());

        verify(productRepository).findPricesByTenant(eq("tenant-a"), eq(20), eq(0));
    }

    @Test
    void createProduct_persistsUnderPrincipalTenant_notSpoofedHeader() throws Exception {
        when(productRepository.saveProduct(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/v1/products")
                        .header("X-Tenant-Id", "victim-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Pro Plan\",\"description\":\"d\"}")
                        .with(authentication(authFor("tenant-a", "admin"))))
                .andExpect(status().isCreated());

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).saveProduct(captor.capture());
        assertThat(captor.getValue().getTenantId())
                .as("product must be created under the authenticated principal's tenant, not the header")
                .isEqualTo("tenant-a");
    }

    @Test
    void listProducts_noPrincipal_isUnauthorized() throws Exception {
        mockMvc.perform(get("/v1/products")
                        .header("X-Tenant-Id", "default"))
                .andExpect(status().isUnauthorized());
    }
}
