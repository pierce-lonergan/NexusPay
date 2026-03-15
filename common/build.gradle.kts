plugins {
    java
}

dependencies {
    implementation(rootProject.libs.moneta)
    implementation(rootProject.libs.spring.boot.starter.validation)

    // Jackson for JSON serialization of value objects
    implementation("com.fasterxml.jackson.core:jackson-annotations")
}
