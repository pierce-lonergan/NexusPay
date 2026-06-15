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
    implementation("org.springframework.boot:spring-boot-starter-aop")     // B-002: @SystemTransactional role aspect
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
    }
}

springBoot {
    mainClass.set("io.nexuspay.NexusPayApplication")
}

// ---------------------------------------------------------------------------
// redteamTest — the INVERSE of the default gate (simulation/red-team env).
//
// The root `subprojects { tasks.withType<Test> { useJUnitPlatform { excludeTags(
// "redteam", "simulation") } } }` block EXCLUDES the attack suite from the
// default `test` task so main stays GREEN (those tests assert secure behavior
// current main lacks). This task does the opposite: it INCLUDES only those tags
// and runs them REPORT-ONLY (the perpetua-gates `redteam-sim` CI job swallows a
// non-zero exit with `|| echo ::warning`). It reuses the SAME compiled
// `test` source set — no separate source set, no duplicated IntegrationTestBase /
// TestSecurityConfig machinery.
//
// CRITICAL: `tasks.withType<Test>` above ALSO matches THIS task and mutates the
// SAME JUnitPlatformOptions instance — its `excludeTags("redteam","simulation")`
// runs first and is NOT replaced by a later `includeTags(...)` call (include and
// exclude accumulate independently; when a tag is in BOTH sets JUnit's exclude
// WINS → 0 tests selected). So we must explicitly RESET the inherited excludes to
// empty here before including, otherwise the report-only job silently runs ZERO
// tests. We clear `excludeTags` (and defensively re-set the include set) so the
// resulting task includes redteam/simulation and excludes nothing.
// It runs after the gate `test` so a local `./gradlew test redteamTest` does the
// gate first. See docs/simulation/README.md for the flip-to-gating plan.
// ---------------------------------------------------------------------------
tasks.register<Test>("redteamTest") {
    description = "Runs the report-only @Tag(\"redteam\")/@Tag(\"simulation\") attack + stress suite."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        // Drop the excludes inherited from the root `subprojects` Test config —
        // without this the shared options carry excludeTags(redteam,simulation)
        // and JUnit's exclude beats our include → nothing runs.
        excludeTags = emptySet()
        includeTags = setOf("redteam", "simulation")
    }
    shouldRunAfter(tasks.named("test"))
}
