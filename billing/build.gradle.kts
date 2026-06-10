plugins {
    java
}

dependencies {
    implementation(project(":common"))
    implementation(project(":ledger"))
    implementation(project(":payment-orchestration"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.flywaydb:flyway-core")
    implementation("com.fasterxml.jackson.core:jackson-databind")
}
