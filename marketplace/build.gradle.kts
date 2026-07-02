plugins {
    java
}

dependencies {
    implementation(project(":common"))
    // GAP-063: split-payment journal postings go through the ledger module's use cases
    // (LedgerSplitDistributionAdapter), mirroring dispute's SEC-24 edge.
    implementation(project(":ledger"))

    implementation(rootProject.libs.spring.boot.starter.web)
    implementation(rootProject.libs.spring.boot.starter.data.jpa)
    implementation(rootProject.libs.spring.boot.starter.validation)
    implementation(rootProject.libs.spring.boot.starter.security)
    implementation(rootProject.libs.spring.kafka)
    implementation(rootProject.libs.flyway.core)

    // SEC-11: Valkey/Redis for the fail-CLOSED payout-scheduler lock (MarketplaceSchedulerLock).
    // Raw coordinate (as billing build.gradle.kts:12 and gateway-api build.gradle.kts:25 do) — the
    // version catalog has no redis alias. Lifting the lock to :common stays forbidden (it would force
    // spring-data-redis onto the foundational :common module — see GatewaySchedulerLock javadoc).
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // GAP-063: the @ApplicationModule declaration in package-info.java (mirror dispute/build.gradle.kts).
    implementation("org.springframework.modulith:spring-modulith-starter-core")

    runtimeOnly(rootProject.libs.postgresql)

    testImplementation(rootProject.libs.spring.boot.starter.security)
    testImplementation(rootProject.libs.spring.security.test)
}
