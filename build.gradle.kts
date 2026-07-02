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
        // Default gate (ci.yml + perpetua-gates `build test jacocoTestReport`) EXCLUDES the
        // report-only attack/stress tags. The @Tag("redteam") suite asserts SECURE behavior that
        // current main does NOT yet have (the SEC-* PRs are unmerged), so it WOULD red the gate —
        // it is excluded here and run only by the report-only `redteamTest` task / CI job.
        // @Tag("simulation") is a reserved co-excluded alias for heavier on-demand in-JVM stress.
        // The classes still COMPILE under `./gradlew build` (they live in app/src/test, compiled by
        // the test compile task even when excluded from execution) so CI verifies buildability.
        // In-gate soak/fuzz are deliberately UNTAGGED so they keep running and raise the ratchet
        // floors. See docs/simulation/README.md for the flip-to-gating plan.
        useJUnitPlatform { excludeTags("redteam", "simulation") }
        // The forked test JVM previously inherited the default max heap (~1/4 of runner RAM). As the
        // @SpringBootTest + Testcontainers integration suite grew, that ceiling was hit on the GitHub-hosted
        // runner and surfaced as an intermittent OutOfMemoryError cascade (seen across PRs #61/#64 as a
        // one-of-two green flake). Pin an explicit, generous fork heap + metaspace so the growing suite has
        // headroom, and recycle the fork JVM periodically so accumulated Spring context caches cannot creep
        // the process toward the ceiling over a long module test run. Leaves ample room for the Docker
        // (Testcontainers) containers + the Gradle process on the runner.
        maxHeapSize = "3g"
        jvmArgs("-XX:+EnableDynamicAgentLoading")
        setForkEvery(60)
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
