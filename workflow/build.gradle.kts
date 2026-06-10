plugins {
    java
}

dependencies {
    implementation(project(":common"))
    implementation(project(":payment-orchestration"))

    // Temporal SDK — durable workflow execution (Sprint 2.2)
    implementation(rootProject.libs.temporal.sdk)

    // Spring Boot for configuration + DI
    implementation(rootProject.libs.spring.boot.starter.web)
    implementation(rootProject.libs.spring.boot.starter.data.jpa)
    implementation(rootProject.libs.spring.boot.starter.validation)
    implementation(rootProject.libs.spring.boot.starter.security)
    implementation(rootProject.libs.spring.modulith.starter.core)
    implementation(rootProject.libs.spring.kafka)
    implementation(rootProject.libs.flyway.core)

    runtimeOnly(rootProject.libs.postgresql)

    testImplementation(rootProject.libs.temporal.testing)
    testImplementation(rootProject.libs.spring.boot.starter.security)
    testImplementation(rootProject.libs.spring.security.test)
}
