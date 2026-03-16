plugins {
    java
    alias(libs.plugins.avro.gradle)
}

dependencies {
    implementation(rootProject.libs.moneta)
    implementation(rootProject.libs.spring.boot.starter.validation)
    implementation(rootProject.libs.avro)

    // Jackson for JSON serialization of value objects
    implementation("com.fasterxml.jackson.core:jackson-annotations")
    implementation("com.fasterxml.jackson.core:jackson-databind")
}

avro {
    setStringType("String")
    setFieldVisibility("PRIVATE")
}
