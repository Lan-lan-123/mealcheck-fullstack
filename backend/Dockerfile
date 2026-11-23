FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace

ARG MAVEN_MIRROR_URL=https://repo.maven.apache.org/maven2
COPY docker/maven-settings.xml /tmp/maven-settings.xml
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -s /tmp/maven-settings.xml -B -ntp -DskipTests dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -s /tmp/maven-settings.xml -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre-alpine-3.24@sha256:2b4113ae80e31f103b284dd3bc3e008add670cf8555417b5b289fa1128f2f0a3
WORKDIR /app

RUN addgroup -S -g 10001 mealcheck \
    && adduser -S -D -H -u 999 -G mealcheck -h /app -s /sbin/nologin mealcheck \
    && mkdir -p /app/uploads /app/upload-temp \
    && chown -R mealcheck:mealcheck /app

COPY --from=build /workspace/target/*.jar app.jar

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=45.0 -XX:+ExitOnOutOfMemoryError"

USER mealcheck
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=5 \
  CMD wget -q -O /dev/null "http://127.0.0.1:${MANAGEMENT_SERVER_PORT:-8080}/actuator/health" || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
