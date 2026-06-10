plugins {
    java
}

dependencies {
    implementation(project(":common"))
    implementation(project(":ledger"))

    // Spring Boot — web + JPA for REST API and persistence
    implementation(rootProject.libs.spring.boot.starter.web)
    implementation(rootProject.libs.spring.boot.starter.data.jpa)
    implementation(rootProject.libs.spring.modulith.starter.core)

    // Flyway — migration path registration
    implementation(rootProject.libs.flyway.core)

    testImplementation(rootProject.libs.spring.boot.starter.test)
}
