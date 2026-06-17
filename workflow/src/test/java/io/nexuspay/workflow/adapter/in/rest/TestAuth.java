package io.nexuspay.workflow.adapter.in.rest;

import io.nexuspay.common.tenant.TenantPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * SEC-27 test helper: builds a {@link TenantPrincipal}-bearing authentication.
 *
 * <p>{@code CallerTenant.require()} resolves the caller's tenant from this principal exactly as the iam
 * {@code NexusPayPrincipal} does in production. The workflow module has no compile dependency on iam, so
 * the controller-slice tests authenticate with this minimal {@code TenantPrincipal} (from {@code :common})
 * instead of the concrete iam principal — letting the tests exercise that the effective tenant is the
 * AUTHENTICATED identity, never a client-supplied X-Tenant-Id header.</p>
 */
final class TestAuth {

    private TestAuth() {
    }

    static Authentication authFor(String tenant, String role) {
        TenantPrincipal principal = () -> tenant;
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
