FROM gradle:8.14.2-jdk21 AS builder

WORKDIR /workspace

COPY settings.gradle* build.gradle* gradle.properties* ./
COPY gradle ./gradle
COPY common ./common
COPY gateway ./gateway
COPY services ./services

ARG SERVICE_PATH
RUN gradle ":${SERVICE_PATH}:bootJar" --no-daemon

FROM eclipse-temurin:21-jre

WORKDIR /app

ARG SERVICE_PATH
COPY --from=builder \
     /workspace/${SERVICE_PATH}/build/libs/*.jar \
     /app/application.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/application.jar"]
