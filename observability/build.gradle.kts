plugins {
    java
}

dependencies {
    implementation(project(":common"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework:spring-jdbc")
    implementation("io.micrometer:micrometer-registry-prometheus")
}
