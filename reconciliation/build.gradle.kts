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

    // Flyway — migration path registration
    implementation(rootProject.libs.flyway.core)

    // RFC-4180-correct CSV parsing for settlement files (B-015). Version is
    // managed by the Spring Boot BOM's Jackson BOM (imported in the root
    // build.gradle.kts), so it is declared version-less — the same pattern the
    // common/billing/fraud/dispute modules use for jackson-databind.
    implementation(rootProject.libs.jackson.dataformat.csv)

    testImplementation(rootProject.libs.spring.boot.starter.test)
}
