plugins {
    java
}

dependencies {
    implementation(project(":common"))

    // Temporal SDK — durable workflow execution (Sprint 2.2)
    implementation(rootProject.libs.temporal.sdk)

    // Spring Boot for configuration + DI
    implementation(rootProject.libs.spring.boot.starter.web)

    testImplementation(rootProject.libs.temporal.testing)
}
