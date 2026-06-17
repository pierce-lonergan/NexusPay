plugins {
    java
}

dependencies {
    implementation(project(":common"))
    implementation(project(":ledger"))
    implementation(project(":payment-orchestration"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.flywaydb:flyway-core")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // SEC-26: the REST controllers now derive the caller's tenant from the authenticated principal
    // (CallerTenant.require(), which reads SecurityContextHolder). The @WebMvcTest controller-slice
    // tests need a security filter chain + the @WithMockUser/authentication() post-processors to put a
    // TenantPrincipal in the context. spring-security-core arrives transitively via :common at runtime,
    // but the test module needs the test fixtures + servlet security autoconfig on its own classpath.
    testImplementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("org.springframework.security:spring-security-test")
}
