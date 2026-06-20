plugins {
    java
}

dependencies {
    implementation(project(":common"))
    implementation(project(":ledger"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // DX-5c-ii: DisputeController now carries @PreAuthorize("... @scopeAuth.has('disputes:*')") method
    // security. The @PreAuthorize type is in spring-security-core; an `implementation` dep on :common
    // does NOT leak common's own spring-security onto dispute's compile classpath, so declare it here
    // (mirrors marketplace/vault, which already depend on spring-boot-starter-security for the same reason).
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.flywaydb:flyway-core")
    implementation("com.fasterxml.jackson.core:jackson-databind")
}
