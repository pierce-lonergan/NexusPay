plugins {
    java
}

dependencies {
    implementation(project(":common"))
    implementation(project(":ledger"))

    // Spring Boot — web + JPA for REST API and persistence
    implementation(rootProject.libs.spring.boot.starter.web)
    implementation(rootProject.libs.spring.boot.starter.data.jpa)
    implementation(rootProject.libs.spring.modulith.starter.core)

    // SEC-27: ReconciliationController now derives the caller's tenant from the authenticated
    // principal (CallerTenant.require()) and guards mutations with @PreAuthorize. CallerTenant /
    // TenantOwnership live in :common, but @PreAuthorize and SecurityContextHolder come from
    // spring-security, which :common only declares as `implementation` (not leaked transitively) —
    // so reconciliation needs it on its own compile classpath, matching the b2b module.
    implementation(rootProject.libs.spring.boot.starter.security)

    // Flyway — migration path registration
    implementation(rootProject.libs.flyway.core)

    // RFC-4180-correct CSV parsing for settlement files (B-015). Version is
    // managed by the Spring Boot BOM's Jackson BOM (imported in the root
    // build.gradle.kts), so it is declared version-less — the same pattern the
    // common/billing/fraud/dispute modules use for jackson-databind.
    implementation(rootProject.libs.jackson.dataformat.csv)

    testImplementation(rootProject.libs.spring.boot.starter.test)

    // SEC-27: @WebMvcTest controller-slice tests need the servlet security autoconfig + test fixtures
    // (authentication() post-processor, etc.) on the test classpath to put a TenantPrincipal in the
    // security context and to enforce @PreAuthorize. Mirrors the b2b/billing module test setup.
    testImplementation(rootProject.libs.spring.boot.starter.security)
    testImplementation(rootProject.libs.spring.security.test)
}
