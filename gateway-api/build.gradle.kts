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

    // SEC-14: Apache HttpClient5 is required for SSRF-safe webhook delivery. The JDK's
    // HttpURLConnection (SimpleClientHttpRequestFactory) resolves the host AGAIN at connect, so the
    // validated-IP set cannot be pinned and a DNS-rebinding TOCTOU remains. HttpClient5 exposes a
    // pluggable DnsResolver, letting WebhookDeliveryService pin the TCP connection to the EXACT IPs
    // the validator approved (TLS SNI/Host stay the hostname so the cert still validates) and disable
    // redirect-following. Version is managed by the Spring Boot BOM (3.2.5 -> httpclient5 5.2.3).
    implementation("org.apache.httpcomponents.client5:httpclient5")

    runtimeOnly(rootProject.libs.postgresql)

    testImplementation(rootProject.libs.spring.security.test)
}
