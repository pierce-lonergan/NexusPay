plugins {
    java
    alias(libs.plugins.avro.gradle)
}

dependencies {
    implementation(rootProject.libs.moneta)
    implementation("org.springframework.modulith:spring-modulith-api")
    implementation(rootProject.libs.spring.boot.starter.validation)
    // SEC-BATCH-1: CallerTenant reads SecurityContextHolder (spring-security-core). Every runtime
    // module already pulls spring-boot-starter-security; common needs it on its own compile
    // classpath because `implementation` deps do not leak transitively to dependents.
    implementation(rootProject.libs.spring.boot.starter.security)
    implementation(rootProject.libs.avro)
    implementation(rootProject.libs.kafka.clients)
    implementation(rootProject.libs.kafka.avro.serializer)
    implementation(rootProject.libs.kafka.schema.registry.client)

    // Jackson for JSON serialization of value objects
    implementation("com.fasterxml.jackson.core:jackson-annotations")
    implementation("com.fasterxml.jackson.core:jackson-databind")
}

avro {
    setStringType("String")
    setFieldVisibility("PRIVATE")
}
