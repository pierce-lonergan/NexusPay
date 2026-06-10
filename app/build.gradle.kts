plugins {
    java
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":common"))
    implementation(project(":gateway-api"))
    implementation(project(":payment-orchestration"))
    implementation(project(":ledger"))
    implementation(project(":iam"))
    implementation(project(":reconciliation"))
    implementation(project(":observability"))
    implementation(project(":dispute"))
    implementation(project(":workflow"))
    implementation(project(":billing"))
    implementation(project(":fraud"))
    implementation(project(":analytics"))
    implementation(project(":vault"))
    implementation(project(":marketplace"))
    implementation(project(":b2b"))

    implementation(rootProject.libs.spring.boot.starter.web)
    implementation(rootProject.libs.spring.boot.starter.actuator)
    implementation(rootProject.libs.spring.boot.starter.security)
    implementation(rootProject.libs.spring.boot.starter.data.jpa)
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation(rootProject.libs.spring.kafka)
    implementation(rootProject.libs.kafka.clients)
    implementation(rootProject.libs.spring.modulith.starter.core)
    implementation(rootProject.libs.spring.modulith.starter.jpa)
    implementation(rootProject.libs.logstash.logback.encoder)

    // Vault — secrets management (Sprint 2.1, activate with 'vault' profile)
    implementation(rootProject.libs.spring.cloud.vault.config)

    // Avro / Schema Registry (Sprint 3.4)
    implementation(rootProject.libs.kafka.avro.serializer)
    implementation(rootProject.libs.kafka.schema.registry.client)

    runtimeOnly(rootProject.libs.postgresql)

    developmentOnly(rootProject.libs.spring.boot.devtools)

    testImplementation(rootProject.libs.spring.modulith.test)
    testImplementation(rootProject.libs.spring.security.test)
    testImplementation(rootProject.libs.spring.boot.starter.oauth2.resource.server)
    testImplementation(rootProject.libs.testcontainers.junit.jupiter)
    testImplementation(rootProject.libs.testcontainers.postgresql)
    testImplementation(rootProject.libs.testcontainers.kafka)
    testImplementation("com.jayway.jsonpath:json-path")
}

// Testcontainers 1.19.x was built against commons-compress 1.24.0; the Spring Boot
// 3.2.5 BOM manages 1.25.0, whose TarArchiveOutputStream trips an
// ExceptionInInitializerError when Testcontainers builds the image context
// (NoClassDefFoundError in the integration tests). Pin 1.24.0 for compatibility.
// commons-compress is test-only here (not a runtime dependency).
configurations.all {
    resolutionStrategy {
        force("org.apache.commons:commons-compress:1.24.0")
        // flyway-core and flyway-database-postgresql MUST be the same version, or
        // FlywayExecutor throws AbstractMethodError (SPI split). The Spring Boot
        // BOM and the version catalog disagreed; pin both to the catalog's 10.15.0.
        force("org.flywaydb:flyway-core:10.15.0")
        force("org.flywaydb:flyway-database-postgresql:10.15.0")
    }
}

springBoot {
    mainClass.set("io.nexuspay.NexusPayApplication")
}
