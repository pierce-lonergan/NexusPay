plugins {
    java
}

dependencies {
    implementation(project(":common"))
    // GAP-069: b2b money transitions post journal entries through the ledger module's use cases
    // (B2bLedgerAdapter), mirroring dispute's SEC-24 edge.
    implementation(project(":ledger"))
    // GAP-068: b2b REUSES the iam maker-checker ApprovalService (atomic claim, requester != reviewer,
    // tenant-checked review) — the same edge gateway-api already uses. Never a copied approvals table.
    implementation(project(":iam"))

    implementation(rootProject.libs.spring.boot.starter.web)
    implementation(rootProject.libs.spring.boot.starter.data.jpa)
    implementation(rootProject.libs.spring.boot.starter.validation)
    implementation(rootProject.libs.spring.boot.starter.security)
    implementation(rootProject.libs.spring.kafka)
    implementation(rootProject.libs.flyway.core)

    // GAP-068/069: the @ApplicationModule declaration in package-info.java (mirror dispute/build.gradle.kts).
    implementation("org.springframework.modulith:spring-modulith-starter-core")

    runtimeOnly(rootProject.libs.postgresql)

    testImplementation(rootProject.libs.spring.boot.starter.security)
    testImplementation(rootProject.libs.spring.security.test)
}
