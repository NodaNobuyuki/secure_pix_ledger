# syntax=docker/dockerfile:1

# ---- Build stage: full JDK + Maven wrapper, discarded after the jar is built ----
FROM eclipse-temurin:21.0.12.1_1-jdk-alpine AS build
WORKDIR /workspace

# Copy build descriptors first so dependency resolution is cached until pom.xml changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src src
# Tests and static analysis run in CI (`./mvnw verify`); the image build only packages.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
    && java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination /extracted

# ---- Runtime stage: JRE only, no build tools or sources ----
FROM eclipse-temurin:21.0.12.1_1-jre-alpine
WORKDIR /app

# Never run the JVM as root: a compromised app should not own the container.
RUN addgroup -S app && adduser -S -G app -H app

# Layers ordered from least to most frequently changed to maximize image layer reuse.
COPY --from=build --chown=app:app /extracted/dependencies/ ./
COPY --from=build --chown=app:app /extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /extracted/application/ ./

USER app
EXPOSE 8080 8081

# Container-aware heap sizing instead of a hardcoded -Xmx.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
