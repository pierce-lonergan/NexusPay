plugins {
    java
}

dependencies {
    implementation(project(":common"))
    implementation(project(":fraud"))   // B-024: pre-auth fraud gate relocated here (GatedPaymentGateway)

    implementation(rootProject.libs.spring.boot.starter.web)
    implementation(rootProject.libs.spring.boot.starter.data.jpa)
    implementation(rootProject.libs.spring.boot.starter.validation)
    implementation(rootProject.libs.spring.boot.starter.security)
    implementation(rootProject.libs.spring.boot.starter.actuator)
    implementation(rootProject.libs.spring.modulith.starter.core)
    implementation(rootProject.libs.resilience4j.spring.boot3)
    implementation(rootProject.libs.spring.kafka)
    implementation(rootProject.libs.flyway.core)

    // Valkey/Redis for webhook deduplication
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    runtimeOnly(rootProject.libs.postgresql)

    testImplementation(rootProject.libs.wiremock.standalone)
    // TEST-3a: CustomerControllerScopeEnforcementTest is this module's first @WebMvcTest security slice; it
    // uses SecurityMockMvcRequestPostProcessors.authentication (spring-security-test). The convention only
    // injects spring-boot-starter-test (JUnit/Mockito/MockMvc), so the security-test artifact is explicit —
    // same testImplementation already declared by billing + fraud for their scope-enforcement slices.
    testImplementation("org.springframework.security:spring-security-test")
}
