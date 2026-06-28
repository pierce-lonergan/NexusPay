package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.common.api.ApiVersion;
import io.nexuspay.common.tenant.CallerMode;
import io.nexuspay.gateway.adapter.in.rest.dto.PingResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TEST-5 (E3): {@code GET /v1/ping} — a lightweight AUTHENTICATED connectivity + credentials check so an
 * integrator can confirm, in one cheap call, that their base URL is reachable AND their API key is valid
 * (and which MODE that key is).
 *
 * <h3>What it does</h3>
 * <p>Returns {@code {ok:true, livemode:<key mode>, api_version:<contract version>}}. A 200 means the key
 * authenticated; a missing/invalid key never reaches the handler (the security filter chain rejects it with
 * 401/403). {@code livemode} is read from the authenticated principal via {@link CallerMode#isLive()} (test
 * key -> {@code false}, live key -> {@code true}) so a caller can confirm test-vs-live.</p>
 *
 * <h3>Invariants (the review hunts these)</h3>
 * <ul>
 *   <li><b>NO tenant leak.</b> {@link io.nexuspay.common.tenant.CallerTenant#require()} is deliberately NOT
 *       called and there is NO tenant field on {@link PingResponse} — the response cannot expose the tenant
 *       id (nor scopes, nor the key, nor any principal detail).</li>
 *   <li><b>Lightest auth.</b> {@code @PreAuthorize("isAuthenticated()")} — ANY valid key/JWT. No role, no
 *       {@code @scopeAuth}, so NO new {@code ApiScope} (ApiScopeTest is intentionally untouched).</li>
 *   <li><b>Single-sourced version.</b> {@code api_version} is {@link ApiVersion#CURRENT}, never a literal.</li>
 * </ul>
 *
 * <p>Distinct from {@code /actuator/health} (infra-only, unauthenticated): ping is an authenticated
 * credentials check, not an infra probe.</p>
 *
 * @since TEST-5
 */
@RestController
@Tag(name = "Connectivity", description = "Authenticated connectivity + credentials check")
public class PingController {

    @GetMapping("/v1/ping")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Authenticated connectivity + credentials check (returns the key's mode)")
    public ResponseEntity<PingResponse> ping() {
        // livemode is read EXCLUSIVELY from the authenticated principal (CallerMode), never a client header.
        // No tenant is resolved or returned — the response cannot leak tenant identity.
        return ResponseEntity.ok(new PingResponse(true, CallerMode.isLive(), ApiVersion.CURRENT));
    }
}
