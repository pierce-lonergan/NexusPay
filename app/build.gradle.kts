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

    implementation(rootProject.libs.spring.boot.starter.web)
    implementation(rootProject.libs.spring.boot.starter.actuator)
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
    testImplementation(rootProject.libs.testcontainers.junit.jupiter)
    testImplementation(rootProject.libs.testcontainers.postgresql)
    testImplementation(rootProject.libs.testcontainers.kafka)
    testImplementation("com.jayway.jsonpath:json-path")
}

springBoot {
    mainClass.set("io.nexuspay.app.NexusPayApplication")
}
