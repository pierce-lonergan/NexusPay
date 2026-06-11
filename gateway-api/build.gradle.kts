plugins {
    java
}

dependencies {
    implementation(project(":common"))
    implementation(project(":payment-orchestration"))
    implementation(project(":ledger"))
    implementation(project(":iam"))
    implementation(project(":fraud"))   // B-003: pre-authorization fraud gate

    implementation(rootProject.libs.spring.boot.starter.web)
    implementation(rootProject.libs.spring.boot.starter.data.jpa)
    implementation(rootProject.libs.spring.boot.starter.validation)
    implementation(rootProject.libs.spring.boot.starter.security)
    implementation(rootProject.libs.spring.boot.starter.oauth2.resource.server)
    implementation(rootProject.libs.spring.modulith.starter.core)
    implementation(rootProject.libs.spring.kafka)
    implementation(rootProject.libs.kafka.clients)
    implementation(rootProject.libs.flyway.core)
    implementation(rootProject.libs.springdoc.openapi.starter.webmvc.ui)
    implementation(rootProject.libs.logstash.logback.encoder)

    // Valkey/Redis for rate limiting and idempotency
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    runtimeOnly(rootProject.libs.postgresql)

    testImplementation(rootProject.libs.spring.security.test)
}
