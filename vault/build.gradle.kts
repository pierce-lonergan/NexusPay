plugins {
    java
}

dependencies {
    implementation(project(":common"))

    implementation(rootProject.libs.spring.boot.starter.web)
    implementation(rootProject.libs.spring.boot.starter.data.jpa)
    implementation(rootProject.libs.spring.boot.starter.validation)
    implementation(rootProject.libs.spring.kafka)
    implementation(rootProject.libs.flyway.core)
    implementation(rootProject.libs.flyway.database.postgresql)

    runtimeOnly(rootProject.libs.postgresql)
}
