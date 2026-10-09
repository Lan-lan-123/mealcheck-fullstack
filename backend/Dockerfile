FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace

ARG MAVEN_MIRROR_URL=https://repo.maven.apache.org/maven2
COPY docker/maven-settings.xml /tmp/maven-settings.xml
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -s /tmp/maven-settings.xml -q -DskipTests dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -s /tmp/maven-settings.xml -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app

RUN groupadd --system mealcheck \
    && useradd --system --gid mealcheck --home-dir /app --shell /usr/sbin/nologin mealcheck \
    && mkdir -p /app/uploads /app/upload-temp \
    && chown -R mealcheck:mealcheck /app

COPY --from=build /workspace/target/*.jar app.jar

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=45.0 -XX:+ExitOnOutOfMemoryError"

USER mealcheck
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=5 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${MANAGEMENT_SERVER_PORT:-8080}; printf "GET /actuator/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n" >&3; IFS= read -r status <&3; [[ "$status" == *" 200 "* ]]' || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
