plugins {
    java
}

dependencies {
    implementation(project(":common"))
    implementation(project(":ledger"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    implementation("com.fasterxml.jackson.core:jackson-databind")
}
