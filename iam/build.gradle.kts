plugins {
    java
}

dependencies {
    implementation(project(":common"))

    implementation(rootProject.libs.spring.boot.starter.web)
    implementation(rootProject.libs.spring.boot.starter.data.jpa)
    implementation(rootProject.libs.spring.boot.starter.security)
    implementation(rootProject.libs.spring.boot.starter.oauth2.resource.server)
    implementation(rootProject.libs.spring.boot.starter.validation)
    implementation(rootProject.libs.spring.boot.starter.actuator)
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation(rootProject.libs.flyway.core)
    implementation(rootProject.libs.flyway.database.postgresql)

    // JJWT — Session token signing/validation (Sprint 3.5)
    implementation(rootProject.libs.jjwt.api)
    runtimeOnly(rootProject.libs.jjwt.impl)
    runtimeOnly(rootProject.libs.jjwt.jackson)

    runtimeOnly(rootProject.libs.postgresql)

    testImplementation(rootProject.libs.spring.security.test)
}
