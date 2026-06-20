# NexusPay application image (DX-6 / Snap critique §2.4).
# ---------------------------------------------------------------------------
# A self-contained way to run the NexusPay app WITHOUT a local JDK or Gradle —
# `docker run` it against the LITE infra stack (docker/docker-compose.lite.yml).
# See docs/LOCAL_DEV.md §3 "Option B: run the app as a container".
#
# Multi-stage: stage 1 compiles the executable Spring Boot bootJar; stage 2 is a
# slim JRE runtime that carries ONLY the jar (no build toolchain, non-root user).
# ---------------------------------------------------------------------------

# ---- stage 1: build the executable bootJar -------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy the full Gradle project. .dockerignore keeps the context lean (no build/,
# .git, .gradle, node_modules, or the checkout-sdk npm workspace — none are needed
# for the Java :app build).
COPY . .

# Package only — tests need Testcontainers/Postgres and cannot run in a plain
# `docker build`; the normal CI gates own the test/coverage/ratchet checks.
RUN chmod +x gradlew && ./gradlew :app:bootJar --no-daemon -x test

# Spring Boot may emit both app-<ver>.jar (executable bootJar) and
# app-<ver>-plain.jar (plain library jar). Copy the EXECUTABLE one to a stable
# path, excluding the -plain artifact, so the runtime stage is version-agnostic.
RUN find app/build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' -exec cp {} /workspace/app.jar \;

# ---- stage 2: slim runtime -----------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

# Run as a non-root system user (never run the payments app as root).
RUN groupadd --system nexuspay && useradd --system --gid nexuspay --home-dir /opt/nexuspay --shell /usr/sbin/nologin nexuspay
WORKDIR /opt/nexuspay

COPY --from=build /workspace/app.jar app.jar
RUN chown -R nexuspay:nexuspay /opt/nexuspay
USER nexuspay

# The app listens on 8090 (application.yml: server.port).
EXPOSE 8090

# JAVA_OPTS is honored for container-aware heap / GC tuning at `docker run` time.
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
