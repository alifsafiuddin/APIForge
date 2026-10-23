# syntax=docker/dockerfile:1
#
# APIForge AI backend. Build context: backend/.
#   docker build -t apiforge-backend backend
# Tests are not run here: CI runs `mvnw verify` (with Testcontainers) before images are built.
# No configuration or secret is baked in: everything comes from environment variables at runtime
# (docs/DEPLOYMENT.md, section 3).

FROM eclipse-temurin:21.0.12.1_1-jdk-alpine AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# Dependencies first, so source changes do not download them again (BuildKit cache mount).
RUN --mount=type=cache,target=/root/.m2 sh mvnw -B -ntp -q dependency:go-offline -DexcludeReactor=true
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 sh mvnw -B -ntp -q package -DskipTests -Djacoco.skip=true \
    && java -Djarmode=tools -jar target/apiforge-backend-*.jar extract --layers --launcher --destination /workspace/extracted

FROM eclipse-temurin:21.0.12.1_1-jre-alpine
RUN addgroup -S -g 10001 apiforge && adduser -S -D -H -u 10001 -G apiforge apiforge
WORKDIR /app
# One layer per Spring Boot layer: dependencies change rarely, the application often.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8081
# Readiness covers the application state and the database (application.yml, management.endpoint.health).
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1:8081/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
