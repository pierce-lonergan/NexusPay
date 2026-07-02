plugins {
    java
}

dependencies {
    implementation(project(":common"))

    implementation(rootProject.libs.spring.boot.starter.web)
    implementation(rootProject.libs.spring.boot.starter.data.jpa)
    implementation(rootProject.libs.spring.boot.starter.validation)
    implementation(rootProject.libs.spring.boot.starter.security)
    implementation(rootProject.libs.spring.kafka)
    implementation(rootProject.libs.flyway.core)

    // GAP-059: VaultKeyRotationMetrics needs the Micrometer MeterRegistry/Counter compile-time
    // types. Only micrometer-core (the metrics API) is required here — the actual registry
    // implementation is contributed at the :app runtime by spring-boot-starter-actuator. Version is
    // managed by the Spring Boot BOM (applied to every subproject in the root build), so it is
    // declared unversioned like observability's micrometer dependency.
    implementation("io.micrometer:micrometer-core")

    runtimeOnly(rootProject.libs.postgresql)

    testImplementation(rootProject.libs.spring.boot.starter.security)
    testImplementation(rootProject.libs.spring.security.test)
}
