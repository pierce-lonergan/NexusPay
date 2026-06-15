plugins {
    java
}

dependencies {
    implementation(project(":common"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.flywaydb:flyway-core")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("io.github.resilience4j:resilience4j-spring-boot3")

    // SEC-BATCH-1: FraudRuleControllerTest is a @WebMvcTest slice driving principal-based auth via
    // SecurityMockMvcRequestPostProcessors (spring-security-test). spring-boot-starter-test is already
    // supplied to every module by the root subprojects block.
    testImplementation("org.springframework.security:spring-security-test")
}
