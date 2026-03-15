plugins {
    java
}

dependencies {
    implementation(project(":common"))

    implementation(rootProject.libs.spring.boot.starter.web)
    implementation(rootProject.libs.spring.boot.starter.data.jpa)
    implementation(rootProject.libs.spring.boot.starter.validation)
    implementation(rootProject.libs.spring.boot.starter.actuator)
    implementation(rootProject.libs.resilience4j.spring.boot3)
    implementation(rootProject.libs.spring.kafka)
    implementation(rootProject.libs.flyway.core)
    implementation(rootProject.libs.flyway.database.postgresql)

    // Valkey/Redis for webhook deduplication
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    runtimeOnly(rootProject.libs.postgresql)

    testImplementation(rootProject.libs.wiremock.standalone)
}
