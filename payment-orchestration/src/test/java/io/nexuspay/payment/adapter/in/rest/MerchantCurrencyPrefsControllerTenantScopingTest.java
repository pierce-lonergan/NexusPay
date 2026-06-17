package io.nexuspay.payment.adapter.in.rest;

import io.nexuspay.common.exception.AuthorizationException;
import io.nexuspay.common.tenant.TenantPrincipal;
import io.nexuspay.payment.application.port.fx.MerchantCurrencyPrefsRepository;
import io.nexuspay.payment.application.port.fx.MerchantCurrencyPrefsRepository.MerchantCurrencyPrefs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SEC-27 tenant-scoping tests for {@link MerchantCurrencyPrefsController}.
 *
 * <p>Both endpoints read/write the caller's preferences. The effective tenant must be the AUTHENTICATED
 * principal's, never a client {@code X-Tenant-Id} header (whose old {@code defaultValue="default"} would
 * have let an unauthenticated/spoofed caller read or overwrite the {@code "default"} tenant's prefs).
 * The persisted row must carry the principal tenant so a caller can never write into another tenant.</p>
 */
class MerchantCurrencyPrefsControllerTenantScopingTest {

    private MerchantCurrencyPrefsRepository prefsRepository;
    private MerchantCurrencyPrefsController controller;

    @BeforeEach
    void setUp() {
        prefsRepository = mock(MerchantCurrencyPrefsRepository.class);
        controller = new MerchantCurrencyPrefsController(prefsRepository);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(String tenant, String role) {
        TenantPrincipal principal = () -> tenant;
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void getPreferences_usesPrincipalTenant_noDefaultFallback() {
        authenticateAs("tenant-a", "viewer");
        when(prefsRepository.findByTenantId("tenant-a")).thenReturn(Optional.empty());

        var response = controller.getPreferences();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // Defaults are synthesised for the PRINCIPAL tenant, not "default".
        assertThat(response.getBody().get("tenant_id")).isEqualTo("tenant-a");
        verify(prefsRepository).findByTenantId("tenant-a");
        verify(prefsRepository, never()).findByTenantId("default");
    }

    @Test
    void updatePreferences_persistsUnderPrincipalTenant_notHeader() {
        authenticateAs("tenant-a", "admin");
        MerchantCurrencyPrefs existing = new MerchantCurrencyPrefs(
                UUID.randomUUID(), "tenant-a", "USD", true, 0, "ECB", 15, "US");
        when(prefsRepository.findByTenantId("tenant-a")).thenReturn(Optional.of(existing));
        when(prefsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new MerchantCurrencyPrefsController.UpdatePrefsRequest(
                "EUR", null, null, null, null);
        controller.updatePreferences(request);

        ArgumentCaptor<MerchantCurrencyPrefs> captor = ArgumentCaptor.forClass(MerchantCurrencyPrefs.class);
        verify(prefsRepository).save(captor.capture());
        assertThat(captor.getValue().tenantId()).isEqualTo("tenant-a");
        verify(prefsRepository, never()).findByTenantId("default");
    }

    @Test
    void getPreferences_noPrincipal_throwsForbidden_neverDefaults() {
        assertThatThrownBy(() -> controller.getPreferences())
                .isInstanceOf(AuthorizationException.class);

        verify(prefsRepository, never()).findByTenantId("default");
        verify(prefsRepository, never()).findByTenantId(any());
    }

    @Test
    void updatePreferences_noPrincipal_throwsForbidden_neverWrites() {
        var request = new MerchantCurrencyPrefsController.UpdatePrefsRequest(
                "EUR", null, null, null, null);

        assertThatThrownBy(() -> controller.updatePreferences(request))
                .isInstanceOf(AuthorizationException.class);

        verify(prefsRepository, never()).save(any());
    }
}
