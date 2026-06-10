import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

plugins {
    java
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

allprojects {
    group = "io.nexuspay"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
        maven("https://packages.confluent.io/maven/")
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "jacoco")   // per-module coverage (B-005); CI aggregates the XMLs vs ratchet floor

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    configure<DependencyManagementExtension> {
        imports {
            mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
            mavenBom("org.springframework.modulith:spring-modulith-bom:${rootProject.libs.versions.spring.modulith.get()}")
            mavenBom("org.testcontainers:testcontainers-bom:${rootProject.libs.versions.testcontainers.get()}")
            mavenBom("org.springframework.cloud:spring-cloud-dependencies:${rootProject.libs.versions.spring.cloud.get()}")
        }
    }

    dependencies {
        // Common test dependencies for all modules
        "testImplementation"(rootProject.libs.spring.boot.starter.test)
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters"))
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        jvmArgs("-XX:+EnableDynamicAgentLoading")
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        // run after tests; XML for CI aggregation, HTML for humans
        mustRunAfter(tasks.named("test"))
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }
}
