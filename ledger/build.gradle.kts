plugins {
    java
}

dependencies {
    implementation(project(":common"))

    implementation(rootProject.libs.spring.boot.starter.data.jpa)
    implementation(rootProject.libs.spring.boot.starter.validation)
    implementation(rootProject.libs.spring.modulith.starter.core)
    implementation(rootProject.libs.spring.kafka)
    implementation(rootProject.libs.flyway.core)
    implementation(rootProject.libs.moneta)

    runtimeOnly(rootProject.libs.postgresql)
}
